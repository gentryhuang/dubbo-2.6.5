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

package com.alibaba.dubbo.rpc.protocol;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AbstractProxyProtocol,实现 AbstractProtocol 抽象类，Proxy协议抽象类。
 * 说明：
 * 1 为其子类HttpProtocol、RestProtocol、HessianProtocol、RmiProtocol、WebServiceProtocol，提供公用的服务暴露、服务引用的 公用方法，
 * 同时定义了一些抽象方法，用于不同子类协议实现类的自定义逻辑
 * 2 注意 DubboProtocol等协议不继承该类，而是直接继承AbstractProtoco，这也说明了Dubbo协议进行服务暴露和引用和 基于Http协议的协议实现是不同的。 todo 这个区别很重要
 */
public abstract class AbstractProxyProtocol extends AbstractProtocol {

    /**
     * 需要抛出的异常类集合，详细: {@link #doRefer(Class, URL)} 方法
     */
    private final List<Class<?>> rpcExceptions = new CopyOnWriteArrayList<Class<?>>();
    /**
     * 代理工厂
     */
    private ProxyFactory proxyFactory;

    public AbstractProxyProtocol() {
    }

    public AbstractProxyProtocol(Class<?>... exceptions) {
        for (Class<?> exception : exceptions) {
            addRpcException(exception);
        }
    }

    public void addRpcException(Class<?> exception) {
        this.rpcExceptions.add(exception);
    }

    public ProxyFactory getProxyFactory() {
        return proxyFactory;
    }

    public void setProxyFactory(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }

    /**
     * 服务暴露
     *
     * @param invoker Service invoker
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> Exporter<T> export(final Invoker<T> invoker) throws RpcException {
        // 获得服务键
        final String uri = serviceKey(invoker.getUrl());
        // 获得服务健对应的 Exporter 对象，若已经暴露，直接返回
        Exporter<T> exporter = (Exporter<T>) exporterMap.get(uri);
        if (exporter != null) {
            return exporter;
        }

        // todo 为什么要把 invoker 生成对应的代理对象，invoker 本身就是一个可调用对象啊？这里的处理就相当于 Dubbo 协议中创建并启动服务
        // 之所以要通过 invoker 创建一个代理对象，是因为 "HTTP协议们" 一般要通过这个代理对象去处理真正的请求
        // todo 执行服务暴露(将 invoker 的代理对象绑定到具体协议的暴露器中),具体逻辑交给子类完成并返回取消暴露的回调 Runnable
        final Runnable runnable = doExport(proxyFactory.getProxy(invoker, true), invoker.getInterface(), invoker.getUrl());

        // 创建 Exporter 对象
        exporter = new AbstractExporter<T>(invoker) {
            /**
             * 基于 AbstractExporter 抽象类 覆写 unexport方法
             */
            @Override
            public void unexport() {
                // 取消服务暴露
                super.unexport();
                // 从缓存中移除对应的 Exporter
                exporterMap.remove(uri);
                // 执行取消暴露的回调
                if (runnable != null) {
                    try {
                        runnable.run();
                    } catch (Throwable t) {
                        logger.warn(t.getMessage(), t);
                    }
                }
            }
        };
        // 添加到 Exporter 集合中
        exporterMap.put(uri, exporter);
        return exporter;
    }

    /**
     * 服务引用
     *
     * @param type Service class 服务接口
     * @param url  URL address for the remote service 服务提供方URL处理后的
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> Invoker<T> refer(final Class<T> type, final URL url) throws RpcException {

        // todo 这一步相当于 Dubbo 协议中创建客户端连接，区别在于使用代理对象进行封装客户端连接。然后再将这个具有远程通信的对象封装为一个 Invoker ，这样就可以不需要关心具体远程通信对象调用细节，这个细节由代理层处理掉了，具体是由 Wrapper 来进行请求分发的
        // todo 执行引用服务且返回远程服务的Service对象，并通过代理工厂获取对应的 Invoker 对象
        final Invoker<T> target = proxyFactory.getInvoker(doRefer(type, url), type, url);


        // 创建 Invoker 对象，通信细节由具体协议创建的 具有远程通信能力的对象 完成。而 Dubbo 协议通信细节由创建的 Invoker(DubboInvoker) 对象中封装的 Client 完成。主要区别原因是：
        // todo 如果代理对象单独使用，不好处理上层调用哪个方法，如果通过封装成 AbstractProxyInvoker ，那么就可以直接使用 Wrapper 来处理方法的分发，然后交给代理对象处理对应的方法调用（最后会被拦截，使用代理对象封装的 client 发送数据）
        // todo 上层会对 invoker 封装一层代理，供业务方使用，业务方可以以面向接口的方式发起调用，调用最终会到达该 invoker ，进而通过具有远程通信的对象处理（target，target 被封装了一层 Wrapper）
        Invoker<T> invoker = new AbstractInvoker<T>(type, url) {
            /**
             * 覆写 doInvoke 方法
             * @param invocation
             * @return
             * @throws Throwable
             */
            @Override
            protected Result doInvoke(Invocation invocation) throws Throwable {
                try {
                    // 执行RPC调用
                    // todo 远程通信细节由 target 完成
                    Result result = target.invoke(invocation);
                    // 若返回结果带有异常，并且是需要抛出的异常，则抛出异常
                    Throwable e = result.getException();
                    if (e != null) {
                        for (Class<?> rpcException : rpcExceptions) {
                            if (rpcException.isAssignableFrom(e.getClass())) {
                                throw getRpcException(type, url, invocation, e);
                            }
                        }
                    }
                    return result;
                } catch (RpcException e) {
                    // 若是未知异常，获得异常对应的错误码
                    if (e.getCode() == RpcException.UNKNOWN_EXCEPTION) {
                        e.setCode(getErrorCode(e.getCause()));
                    }
                    throw e;
                } catch (Throwable e) {
                    // 抛出 RpcException 异常
                    throw getRpcException(type, url, invocation, e);
                }
            }
        };

        // 添加到Invoker 集合
        invokers.add(invoker);
        return invoker;
    }

    protected RpcException getRpcException(Class<?> type, URL url, Invocation invocation, Throwable e) {
        RpcException re = new RpcException("Failed to invoke remote service: " + type + ", method: " + invocation.getMethodName() + ", cause: " + e.getMessage(), e);
        re.setCode(getErrorCode(e));
        return re;
    }

    /**
     * 获取URL中的 bind.ip 和 bind.port
     *
     * @param url
     * @return
     */
    protected String getAddr(URL url) {
        // 先从URL中获取bind.ip的值作为ip的值
        String bindIp = url.getParameter(Constants.BIND_IP_KEY, url.getHost());
        // 如果URL中设置了anyhost属性，那么ip的值取 0.0.0.0
        if (url.getParameter(Constants.ANYHOST_KEY, false)) {
            bindIp = Constants.ANYHOST_VALUE;
        }
        // 返回： ip:port
        return NetUtils.getIpByHost(bindIp) + ":" + url.getParameter(Constants.BIND_PORT_KEY, url.getPort());
    }

    /**
     * 获得异常对应的错误码，子类协议实现类一般会覆写这个方法
     *
     * @param e 异常
     * @return 错误码
     */
    protected int getErrorCode(Throwable e) {
        return RpcException.UNKNOWN_EXCEPTION;
    }

    /**
     * 执行暴露，并返回取消暴露的回调 Runnable
     * <p>
     * todo 核心点，将 impl 绑定到底层的具体协议的暴露器上
     *
     * @param impl 服务 Proxy 对象
     * @param type 服务接口类型
     * @param url  URL
     * @param <T>  服务接口
     * @return 取消服务暴露的回调 Runnable
     * @throws RpcException
     */
    protected abstract <T> Runnable doExport(T impl, Class<T> type, URL url) throws RpcException;

    /**
     * 执行引用，并返回调用远程服务的Service 对象
     * <p>
     * todo 核心点，根据入参创建一个具有远程通信能力的代理对象
     *
     * @param type 服务接口
     * @param url  服务提供者URl处理后的
     * @param <T>  服务接口
     * @return 调用远程服务的Service 对象
     * @throws RpcException
     */
    protected abstract <T> T doRefer(Class<T> type, URL url) throws RpcException;

}
