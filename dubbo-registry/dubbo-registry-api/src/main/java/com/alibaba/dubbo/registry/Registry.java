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
package com.alibaba.dubbo.registry;

import com.alibaba.dubbo.common.Node;

/**
 * Registry. (SPI, Prototype, ThreadSafe)
 * <p>
 * 1 继承了RegistryService 接口，拥有注册、订阅、查询三种操作操作
 * 2 继承了dubbo 的 Node 接口，拥有节点相关的方法
 * 3 定义很多的操作，但具体和注册中心交互的还是注册中心的客户端的
 *
 * @see com.alibaba.dubbo.registry.RegistryFactory#getRegistry(com.alibaba.dubbo.common.URL)
 * @see com.alibaba.dubbo.registry.support.AbstractRegistry
 */
public interface Registry extends Node, RegistryService {
}