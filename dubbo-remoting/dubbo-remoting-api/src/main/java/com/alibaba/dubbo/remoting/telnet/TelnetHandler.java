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
package com.alibaba.dubbo.remoting.telnet;

import com.alibaba.dubbo.common.extension.SPI;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.RemotingException;

/**
 * TelnetHandler  telnet命令处理器
 *
 * @SPI 注解，Dubbo SPI拓展点，每种telnet命令对应一个TelnetHandler实现类，用于处理对应的telnet命令，返回结果
 */
@SPI
public interface TelnetHandler {

    /**
     * 处理telnet命令
     *
     * @param channel 通道
     * @param message telnet 命令
     */
    String telnet(Channel channel, String message) throws RemotingException;

}