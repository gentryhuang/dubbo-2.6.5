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
package com.alibaba.dubbo.rpc.proxy;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcResult;

import java.lang.reflect.InvocationTargetException;

/**
 * 实现Invoker接口，代理Invoker对象的抽象类
 */
public abstract class AbstractProxyInvoker<T> implements Invoker<T> {

    /**
     * 代理的对象，一般是服务接口的实现对象
     */
    private final T proxy;

    /**
     * 接口类型，一般是服务接口
     */
    private final Class<T> type;

    /**
     * URL对象，一般是暴露服务的URL对象
     */
    private final URL url;

    public AbstractProxyInvoker(T proxy, Class<T> type, URL url) {
        if (proxy == null) {
            throw new IllegalArgumentException("proxy == null");
        }
        if (type == null) {
            throw new IllegalArgumentException("interface == null");
        }
        if (!type.isInstance(proxy)) {
            throw new IllegalArgumentException(proxy.getClass().getName() + " not implement interface " + type);
        }
        this.proxy = proxy;
        this.type = type;
        this.url = url;
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
        return true;
    }

    /**
     * 销毁
     * 说明：
     * 在通过JavassistProxyFactory工厂创建Invoker时，就是创建了AbstractProxyInvoker抽象匿名对象
     */
    @Override
    public void destroy() {
    }

    /**
     * 主要逻辑在doInvoke模版方法中实现
     *
     * @param invocation
     * @return
     * @throws RpcException
     */
    @Override
    public Result invoke(Invocation invocation) throws RpcException {
        try {
            // 调用 doInvoke 执行后续的调用，并将调用结果封装到 RpcResult 中
            return new RpcResult(doInvoke(proxy, invocation.getMethodName(), invocation.getParameterTypes(), invocation.getArguments()));
        } catch (InvocationTargetException e) {
            // 发生InvocationTargetException 异常，创建RpcResult对象包装
            return new RpcResult(e.getTargetException());
        } catch (Throwable e) {
            throw new RpcException("Failed to invoke remote proxy method " + invocation.getMethodName() + " to " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 由具体子类覆写 【默认情况使用 JavassistProxyFactory 创建的一个匿名类对象】
     *
     * @param proxy          服务实例
     * @param methodName     方法名
     * @param parameterTypes 方法参数类型数组
     * @param arguments      方法参数数组
     * @return 调用结果
     * @throws Throwable 发生异常
     */
    protected abstract Object doInvoke(T proxy, String methodName, Class<?>[] parameterTypes, Object[] arguments) throws Throwable;

    @Override
    public String toString() {
        return getInterface() + " -> " + (getUrl() == null ? " " : getUrl().toString());
    }


}
