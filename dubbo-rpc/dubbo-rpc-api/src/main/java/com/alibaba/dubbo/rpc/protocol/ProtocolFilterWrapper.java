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
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;

import java.util.List;

/**
 * ListenerProtocol
 */
public class ProtocolFilterWrapper implements Protocol {
    // 扩展点
    private final Protocol protocol;

    // Wrapper 的格式
    public ProtocolFilterWrapper(Protocol protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("protocol == null");
        }
        this.protocol = protocol;
    }

    @Override
    public int getDefaultPort() {
        return protocol.getDefaultPort();
    }

    /**
     * 创建带Filter链的Invoker 对象
     *
     * @param invoker Invoker对象
     * @param key     URL中参数名 【如：用于获得ServiceConfig或ReferenceConfig配置的自定义过滤器】
     * @param group   分组 【暴露服务时：group=provider; 引用服务时：group=consumer】
     * @param <T>
     * @return 在执行的时候执行Filter 
     */
    private static <T> Invoker<T> buildInvokerChain(final Invoker<T> invoker, String key, String group) {
        // 保存引用，后续用于将真正的Invoker 挂到过滤器链的最后
        Invoker<T> last = invoker;
        // 获取所有的过滤器，包括类上带有@Active注解的和用户在XML中配置的
        List<Filter> filters = ExtensionLoader.getExtensionLoader(Filter.class).getActivateExtension(invoker.getUrl(), key, group);
        // 倒序循环 Filter，递归包装Invoker，就是一个链表结构： Xx1Filter->Xx2Filter->Xx3Filter->...->Invoker
        if (!filters.isEmpty()) {
            for (int i = filters.size() - 1; i >= 0; i--) {
                final Filter filter = filters.get(i);
                final Invoker<T> next = last;
                //匿名 Invoker 对象
                last = new Invoker<T>() {
                    @Override
                    public Class<T> getInterface() {
                        return invoker.getInterface();
                    }

                    @Override
                    public URL getUrl() {
                        return invoker.getUrl();
                    }

                    @Override
                    public boolean isAvailable() {
                        return invoker.isAvailable();
                    }

                    /**
                     * 调用Invoker的invoke方法的时候会执行
                     *  1 调用Filter#invoke(invoker,invocation)方法，不断执行过滤器逻辑
                     *  2 在Filter中会调用Invoker#invoker(invocation)方法，最后会执行到Invoker【如：InjvmInvoker,DubboInvoker等】的invoke方法
                     *
                     * @param invocation
                     * @return
                     * @throws RpcException
                     */
                    @Override
                    public Result invoke(Invocation invocation) throws RpcException {
                        return filter.invoke(next, invocation);
                    }

                    @Override
                    public void destroy() {
                        invoker.destroy();
                    }

                    @Override
                    public String toString() {
                        return invoker.toString();
                    }
                };
            }
        }
        return last;
    }


    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        // 1 如果是注册中心协议，无需创建Filter过滤器。
        if (Constants.REGISTRY_PROTOCOL.equals(invoker.getUrl().getProtocol())) {
            return protocol.export(invoker);
        }
        // 2 建立带有Filter 过滤链的 Invoker，暴露服务。
        // Constants.PROVIDER 标识自己是服务提供者类型的调用链
        return protocol.export(buildInvokerChain(invoker, Constants.SERVICE_FILTER_KEY, Constants.PROVIDER));
    }

    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        //  1 如果是注册中心协议，无需创建Filter过滤器。
        if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
            return protocol.refer(type, url);
        }
        /**
         * 1 引用服务，返回 Invoker 对象
         * 2 引用服务完成后，调用 buildInvokerChain(invoker,key,group)方法，创建带有Filter过滤器的Invoker对象。和服务暴露区别在group的值上，
         *   Constants.CONSUMER 标识自己是消费类型的调用链
         */
        return buildInvokerChain(protocol.refer(type, url), Constants.REFERENCE_FILTER_KEY, Constants.CONSUMER);
    }

    /**
     * 关闭，没有实际逻辑，只是包了一层
     */
    @Override
    public void destroy() {
        protocol.destroy();
    }

}
