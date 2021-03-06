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
package com.alibaba.dubbo.remoting.http;

import com.alibaba.dubbo.common.Resetable;
import com.alibaba.dubbo.common.URL;

import java.net.InetSocketAddress;

/**
 * 实现 Resetable 接口，HTTP服务器接口
 * 说明：
 * 1 提供服务在这里
 * 2 使用在dubbo-rpc模块中 http://、rest://、hessian://、webservice:// 协议上，这几个都是基于Http服务器实现请求处理的
 */
public interface HttpServer extends Resetable {

    /**
     * get http handler. 获取处理器
     *
     * @return http handler.
     */
    HttpHandler getHttpHandler();

    /**
     * get url.
     *
     * @return url
     */
    URL getUrl();

    /**
     * get local address.
     *
     * @return local address.
     */
    InetSocketAddress getLocalAddress();

    /**
     * close the channel.
     */
    void close();

    /**
     * Graceful close the channel.
     */
    void close(int timeout);

    /**
     * is bound.
     *
     * @return bound
     */
    boolean isBound();

    /**
     * is closed.
     *
     * @return closed
     */
    boolean isClosed();

}