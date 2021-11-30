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
package com.alibaba.dubbo.rpc;

import java.util.Map;

/**
 * Invocation. (API, Prototype, NonThreadSafe)  // 抽象了一次RPC调用所需的目标服务和方法信息
 *
 * @serial Don't change the class name and package name.
 * @see com.alibaba.dubbo.rpc.Invoker#invoke(Invocation)
 * @see com.alibaba.dubbo.rpc.RpcInvocation
 */
public interface Invocation {
    /**
     * 调用的方法名称
     *
     * @return method name.
     * @serial
     */
    String getMethodName();

    /**
     * 参数类型集合
     *
     * @return parameter types.
     * @serial
     */
    Class<?>[] getParameterTypes();

    /**
     * 此次调用具体的参数值
     *
     * @return arguments.
     * @serial
     */
    Object[] getArguments();

    /**
     * Invocation可以携带KV信息作为附加信息，一并传递给Provider。
     * 注意：attachment 和 attribute 的区别
     *
     * @return attachments.
     * @serial
     */
    Map<String, String> getAttachments();

    /**
     * get attachment by key.
     *
     * @return attachment value.
     * @serial
     */
    String getAttachment(String key);

    /**
     * get attachment by key with default value.
     *
     * @return attachment value.
     * @serial
     */
    String getAttachment(String key, String defaultValue);

    /**
     * 此次调用关联的Invoker 对象
     *
     * @return invoker.
     * @transient
     */
    Invoker<?> getInvoker();
}