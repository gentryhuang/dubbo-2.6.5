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
package com.alibaba.dubbo.remoting.exchange.support;

import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.exchange.ExchangeChannel;
import com.alibaba.dubbo.remoting.exchange.ExchangeHandler;
import com.alibaba.dubbo.remoting.telnet.support.TelnetHandlerAdapter;

/**
 * ExchangeHandlerAdapter 实现 ExchangeHandler 接口，继承 TelnetHandlerAdapter 抽象类，信息交换处理器适配器抽象类
 * 注意： 在DubboProtocol、ThirftProtocol中，都会基于 ExchangeHandlerAdapter实现自己的处理器，处理请求，返回结果
 */
public abstract class ExchangeHandlerAdapter extends TelnetHandlerAdapter implements ExchangeHandler {

    /**
     * 处理请求
     *
     * @param channel 通道
     * @param msg
     * @return
     * @throws RemotingException
     */
    @Override
    public Object reply(ExchangeChannel channel, Object msg) throws RemotingException {
        return null;
    }
}
