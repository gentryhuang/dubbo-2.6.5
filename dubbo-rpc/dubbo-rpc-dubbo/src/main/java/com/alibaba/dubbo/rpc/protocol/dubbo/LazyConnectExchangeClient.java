/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.rpc.protocol.dubbo;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.Parameters;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.exchange.ExchangeClient;
import com.alibaba.dubbo.remoting.exchange.ExchangeHandler;
import com.alibaba.dubbo.remoting.exchange.Exchangers;
import com.alibaba.dubbo.remoting.exchange.ResponseFuture;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * LazyConnectExchangeClient 也是 ExchangeClient 的装饰器，它会在原有 ExchangeClient 对象的基础上添加懒加载的功能。
 * LazyConnectExchangeClient 在构造方法中不会创建底层持有连接的 Client，而是在需要发送请求的时候，才会调用 initClient() 方法进行 Client 的创建。
 */
@SuppressWarnings("deprecation")
final class LazyConnectExchangeClient implements ExchangeClient {
    private final static Logger logger = LoggerFactory.getLogger(LazyConnectExchangeClient.class);

    // 延迟连接告警配置项
    static final String REQUEST_WITH_WARNING_KEY = "lazyclient_request_with_warning";

    /**
     * 请求时，是否检查告警
     */
    protected final boolean requestWithWarning;
    /**
     * URL
     */
    private final URL url;
    /**
     * 通道处理器
     */
    private final ExchangeHandler requestHandler;
    /**
     * 连接锁
     */
    private final Lock connectLock = new ReentrantLock();
    /**
     * 懒连接初始化状态
     */
    private final boolean initialState;
    /**
     * 通信客户端
     */
    private volatile ExchangeClient client;
    /**
     * 警告计数器。每超过一定次数，打印告警日志。参见 {@link #warning(Object)}
     */
    private AtomicLong warningcount = new AtomicLong(0);

    /**
     * 构造方法中并没有立即创建连接，而是在发送请求的时候
     *
     * @param url
     * @param requestHandler
     */
    public LazyConnectExchangeClient(URL url, ExchangeHandler requestHandler) {
        // 延迟连接需要设置 send.reconnect 为 true，防止通道不良状态
        this.url = url.addParameter(Constants.SEND_RECONNECT_KEY, Boolean.TRUE.toString());
        this.requestHandler = requestHandler;
        // 懒连接初始化状态默认值为 true
        this.initialState = url.getParameter(Constants.LAZY_CONNECT_INITIAL_STATE_KEY, Constants.DEFAULT_LAZY_CONNECT_INITIAL_STATE);
        this.requestWithWarning = url.getParameter(REQUEST_WITH_WARNING_KEY, false);
    }

    /**
     * 初始化客户端
     *
     * @throws RemotingException
     */
    private void initClient() throws RemotingException {
        // 已初始化，则跳过
        if (client != null) {
            return;
        }
        if (logger.isInfoEnabled()) {
            logger.info("Lazy connect to " + url);
        }
        // 获得锁
        connectLock.lock();
        try {
            // 已初始化，跳过
            if (client != null) {
                return;
            }
            // 创建Client,连接服务器
            this.client = Exchangers.connect(url, requestHandler);
        } finally {
            // 释放锁
            connectLock.unlock();
        }
    }

    @Override
    public ResponseFuture request(Object request) throws RemotingException {
        warning(request);
        initClient();
        return client.request(request);
    }

    @Override
    public ResponseFuture request(Object request, int timeout) throws RemotingException {
        warning(request);
        initClient();
        return client.request(request, timeout);
    }

    /**
     * 发送消息/请求前，都会调用该方法，确保客户端已经初始化
     *
     * @param message
     * @throws RemotingException
     */
    @Override
    public void send(Object message) throws RemotingException {
        initClient();
        client.send(message);
    }

    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        initClient();
        client.send(message, sent);
    }

    // Endpoint
    @Override
    public URL getUrl() {
        return url;
    }

    // Endpoint
    @Override
    public InetSocketAddress getLocalAddress() {
        if (client == null) {
            return InetSocketAddress.createUnresolved(NetUtils.getLocalHost(), 0);
        } else {
            return client.getLocalAddress();
        }
    }

    // Channel
    @Override
    public InetSocketAddress getRemoteAddress() {
        if (client == null) {
            return InetSocketAddress.createUnresolved(url.getHost(), url.getPort());
        } else {
            return client.getRemoteAddress();
        }
    }


    /**
     * If {@link #REQUEST_WITH_WARNING_KEY} is configured, then warn once every 5000 invocations.
     *
     * @param request
     */
    private void warning(Object request) {
        if (requestWithWarning) {
            if (warningcount.get() % 5000 == 0) {
                logger.warn(new IllegalStateException("safe guard client , should not be called ,must have a bug."));
            }
            warningcount.incrementAndGet();
        }
    }

   // Endpoint
    @Override
    public ChannelHandler getChannelHandler() {
        // 连接不可用则抛出异常
        checkClient();
        return client.getChannelHandler();
    }

    // Endpoint
    @Override
    public boolean isConnected() {
        // 客户端没有初始化
        if (client == null) {
            return initialState;
        } else {
            return client.isConnected();
        }
    }

    // ExchangeChannel
    @Override
    public ExchangeHandler getExchangeHandler() {
        return requestHandler;
    }


    // Endpoint
    @Override
    public boolean isClosed() {
        if (client != null)
            return client.isClosed();
        else
            return true;
    }

    @Override
    public void close() {
        if (client != null)
            client.close();
    }

    @Override
    public void close(int timeout) {
        if (client != null)
            client.close(timeout);
    }

    @Override
    public void startClose() {
        if (client != null) {
            client.startClose();
        }
    }

    @Override
    public void reset(URL url) {
        checkClient();
        client.reset(url);
    }

    @Override
    @Deprecated
    public void reset(Parameters parameters) {
        reset(getUrl().addParameters(parameters.getParameters()));
    }

    @Override
    public void reconnect() throws RemotingException {
        checkClient();
        client.reconnect();
    }

    @Override
    public Object getAttribute(String key) {
        if (client == null) {
            return null;
        } else {
            return client.getAttribute(key);
        }
    }

    @Override
    public void setAttribute(String key, Object value) {
        checkClient();
        client.setAttribute(key, value);
    }

    @Override
    public void removeAttribute(String key) {
        checkClient();
        client.removeAttribute(key);
    }

    @Override
    public boolean hasAttribute(String key) {
        if (client == null) {
            return false;
        } else {
            return client.hasAttribute(key);
        }
    }

    private void checkClient() {
        if (client == null) {
            throw new IllegalStateException(
                    "LazyConnectExchangeClient state error. the client has not be init .url:" + url);
        }
    }
}
