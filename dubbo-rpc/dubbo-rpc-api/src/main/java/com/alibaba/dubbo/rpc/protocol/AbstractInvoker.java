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
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AbstractInvoker. 实现Invoker接口，抽象Invoker类，主要提供了Invoker的通用属性和#invoke(Invocation)方法的通用实现[具体实现交给子类]
 */
public abstract class AbstractInvoker<T> implements Invoker<T> {

    protected final Logger logger = LoggerFactory.getLogger(getClass());
    /**
     * 该 Invoker 对象封装的业务接口
     */
    private final Class<T> type;
    /**
     * 与当前 Invoker 关联的 URL 对象，其中包含了全部的配置信息
     */
    private final URL url;
    /**
     * 当前 Invoker 关联的一些附加信息，这些附加信息可以来自关联的 URL.
     * 共用的隐式传参，在{@link #invoke(Invocation)}方法中使用
     */
    private final Map<String, String> attachment;
    /**
     * 当前 Invoker 的状态 - 是否可用
     */
    private volatile boolean available = true;
    /**
     * 当前 Invoker 的状态 - 是否销毁
     */
    private AtomicBoolean destroyed = new AtomicBoolean(false);

    public AbstractInvoker(Class<T> type, URL url) {
        this(type, url, (Map<String, String>) null);
    }

    /**
     * 调用 convertAttachment() 方法，会从关联的 URL 对象获取指定的 KV 记录到 attachment 中
     *
     * @param type
     * @param url
     * @param keys
     */
    public AbstractInvoker(Class<T> type, URL url, String[] keys) {
        this(type, url, convertAttachment(url, keys));
    }

    public AbstractInvoker(Class<T> type, URL url, Map<String, String> attachment) {
        if (type == null) {
            throw new IllegalArgumentException("service type == null");
        }
        if (url == null) {
            throw new IllegalArgumentException("service url == null");
        }
        this.type = type;
        this.url = url;
        this.attachment = attachment == null ? null : Collections.unmodifiableMap(attachment);
    }

    private static Map<String, String> convertAttachment(URL url, String[] keys) {
        if (keys == null || keys.length == 0) {
            return null;
        }
        Map<String, String> attachment = new HashMap<String, String>();
        for (String key : keys) {
            String value = url.getParameter(key);
            if (value != null && value.length() > 0) {
                attachment.put(key, value);
            }
        }
        return attachment;
    }

    @Override
    public Class<T> getInterface() {
        return type;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    protected void setAvailable(boolean available) {
        this.available = available;
    }

    /**
     * 标记已经销毁
     */
    @Override
    public void destroy() {
        // destroyed 设置为 true
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        // available 设置为 false
        setAvailable(false);
    }

    /**
     * 是否销毁
     *
     * @return
     */
    public boolean isDestroyed() {
        return destroyed.get();
    }

    @Override
    public String toString() {
        return getInterface() + " -> " + (getUrl() == null ? "" : getUrl().toString());
    }

    /**
     * 服务调用，重新设置了 Invocation 中的invoker 属性和 attachment 属性
     *
     * @param inv
     * @return
     * @throws RpcException
     */
    @Override
    public Result invoke(Invocation inv) throws RpcException {
        // if invoker is destroyed due to address refresh from registry, let's allow the current invoke to proceed
        if (destroyed.get()) {
            logger.warn("Invoker for service " + this + " on consumer " + NetUtils.getLocalHost() + " is destroyed, "
                    + ", dubbo version is " + Version.getVersion() + ", this invoker should not be used any longer");
        }

        // 1 将传入的 Invocation 转为 RpcInvocation
        RpcInvocation invocation = (RpcInvocation) inv;
        // 注意： 执行到这里是真正的Invoker，之前的Invoker都是层层嵌套
        invocation.setInvoker(this);

        // 2 将 attachment集合添加为 Invocation 的附加信息
        if (attachment != null && attachment.size() > 0) {
            invocation.addAttachmentsIfAbsent(attachment);
        }

        /**
         * 3 将 RpcContext 的附加信息添加到 Invocation 的附加信息中。注意使用RpcContext进行隐式传参。
         */
        Map<String, String> contextAttachments = RpcContext.getContext().getAttachments();
        if (contextAttachments != null && contextAttachments.size() != 0) {
            /**
             * invocation.addAttachmentsIfAbsent(context){@link RpcInvocation#addAttachmentsIfAbsent(Map)}should not be used here,
             * because the {@link RpcContext#setAttachment(String, String)} is passed in the Filter when the call is triggered
             * by the built-in retry mechanism of the Dubbo. The attachment to update RpcContext will no longer work, which is
             * a mistake in most cases (for example, through Filter to RpcContext output traceId and spanId and other information).
             */
            invocation.addAttachments(contextAttachments);
        }

        // 4 如果方法是异步的就设置异步标志
        if (getUrl().getMethodParameter(invocation.getMethodName(), Constants.ASYNC_KEY, false)) {
            // 4.1 设置异步信息到 RpcInvocation#attachment 中
            invocation.setAttachment(Constants.ASYNC_KEY, Boolean.TRUE.toString());
        }
        //  4.2 如果是异步调用，给invocation的attachment 添加一个 id 属性，并设置唯一ID值。
        RpcUtils.attachInvocationIdIfAsync(getUrl(), invocation);


        try {
            // 5 执行调用，由不同协议对应具体的子类实现
            return doInvoke(invocation);
        } catch (InvocationTargetException e) { // biz exception
            Throwable te = e.getTargetException();
            if (te == null) {
                return new RpcResult(e);
            } else {
                if (te instanceof RpcException) {
                    ((RpcException) te).setCode(RpcException.BIZ_EXCEPTION);
                }
                return new RpcResult(te);
            }
        } catch (RpcException e) {
            if (e.isBiz()) {
                return new RpcResult(e);
            } else {
                throw e;
            }
        } catch (Throwable e) {
            return new RpcResult(e);
        }
    }

    /**
     * 子类实现调用逻辑
     *
     * @param invocation
     * @return
     * @throws Throwable
     */
    protected abstract Result doInvoke(Invocation invocation) throws Throwable;

}
