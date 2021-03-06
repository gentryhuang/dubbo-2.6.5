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
package com.alibaba.dubbo.remoting.zookeeper;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;

/**
 * Zookeeper 客户端工厂接口
 * <p>
 * 1 @SPI("curator") 注解，使用 Dubbo SPI 机制，默认使用 Curator 实现
 * 2 @Adaptive({Constants.CLIENT_KEY, Constants.TRANSPORTER_KEY}) 注解，使用 Dubbo SPI Adaptive 机制，根据 url 参数，加载对应的 ZookeeperTransporter 拓展实现类
 */
@SPI("curator")
public interface ZookeeperTransporter {

    /**
     * 连接创建 ZookeeperClient 对象
     *
     * @param url 注册中心地址
     * @return ZookeeperClient 对象
     */
    @Adaptive({Constants.CLIENT_KEY, Constants.TRANSPORTER_KEY})
    ZookeeperClient connect(URL url);

}
