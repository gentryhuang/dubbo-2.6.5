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
package com.alibaba.dubbo.rpc.protocol.injvm;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.protocol.AbstractInvoker;

import java.util.Map;

/**
 * InjvmInvoker  继承AbstractInvoker抽象类
 */
class InjvmInvoker<T> extends AbstractInvoker<T> {

    /**
     * 服务键
     */
    private final String key;
    /**
     * Exporter集合，在InjvmInvoker#invoke(invocation)方法中，可以通过该Invoker的key属性，获得对应的Exporter对象
     * key: 服务键
     * 该值实际就是 {@link com.alibaba.dubbo.rpc.protocol.AbstractProtocol#exporterMap}。
     */
    private final Map<String, Exporter<?>> exporterMap;

    InjvmInvoker(Class<T> type, URL url, String key, Map<String, Exporter<?>> exporterMap) {
        super(type, url);
        this.key = key;
        this.exporterMap = exporterMap;
    }

    /**
     * 是否可用。开启启动时检查时，调用该方法，判断该Invoker对象是否有对应的Exporter，若不存在，说明以来服务不存在，检查不通过
     *
     * @return
     */
    @Override
    public boolean isAvailable() {
        // 判断是否有Exporter对象
        InjvmExporter<?> exporter = (InjvmExporter<?>) exporterMap.get(key);
        if (exporter == null) {
            return false;
        } else {
            return super.isAvailable();
        }
    }

    /**
     * 调用，本质上就是利用公用的 Map
     *
     * @param invocation
     * @return
     * @throws Throwable
     */
    @Override
    public Result doInvoke(Invocation invocation) throws Throwable {
        /**
         *  1 根据服务键从 {@link InjvmInvoker#exporterMap}缓存中 获取 Exporter
         */
        Exporter<?> exporter = InjvmProtocol.getExporter(exporterMap, getUrl());
        if (exporter == null) {
            throw new RpcException("Service [" + key + "] not found.");
        }

        // 2 设置服务提供者地址为本地
        RpcContext.getContext().setRemoteAddress(NetUtils.LOCALHOST, 0);


        // 3 从Exporter中拿到Invoker，然后调用invoke方法,
        return exporter.getInvoker().invoke(invocation);
    }
}
