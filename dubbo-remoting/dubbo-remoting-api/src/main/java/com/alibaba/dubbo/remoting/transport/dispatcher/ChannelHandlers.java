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
package com.alibaba.dubbo.remoting.transport.dispatcher;


import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.Dispatcher;
import com.alibaba.dubbo.remoting.exchange.support.header.HeartbeatHandler;
import com.alibaba.dubbo.remoting.transport.MultiMessageHandler;

/**
 * 通道处理器工厂
 */
public class ChannelHandlers {

    /**
     * 单例
     */
    private static ChannelHandlers INSTANCE = new ChannelHandlers();

    protected ChannelHandlers() {
    }

    /**
     * 无论是Client还是Server，都是类似的，将传入的ChannelHandler使用ChannelHandlers进行一次包装。 其中实现了 Dubbo 线程模型
     *
     * @param handler
     * @param url
     * @return
     */
    public static ChannelHandler wrap(ChannelHandler handler, URL url) {
        return ChannelHandlers.getInstance().wrapInternal(handler, url);
    }

    protected static ChannelHandlers getInstance() {
        return INSTANCE;
    }

    static void setTestingChannelHandlers(ChannelHandlers instance) {
        INSTANCE = instance;
    }

    /**
     * 说明：无论是请求还是响应都会按照这个顺序处理一遍，多个 ChannelHandlerDelegate 的组合
     * <p>
     * 1  对DecodeHandler对象进行层层包装，最终得到MultiMessageHandler：
     * MultiMessageHandler->HeartbeatHandler->AllChangeHandler[url:providerUrl,executor:FixedExecutor,handler:DecodeHandler] -> DecodeHandler -> HeaderExchangeHandler->ExchangeHandlerAdapter
     * 2 MultiMessageHandler 创建后，NettyServer就开始调用各个父类进行属性初始化
     * <p>
     * 注意点：
     * 1 无论是Server还是Client ，使用该方法包装传入的handler
     *
     * todo 特别点
     * Channel 准备就绪时会触发对应的 ChannelHandler 方法，如果当前 ChannelHandler 没有该方法，则会交给装饰的 ChannelHandler 处理，以此类推。如：
     * 当 Netty 的 Channel 有数据写入时，write 方法就会触发：
     * 1 NettyServerHandler 会调用装饰的 NettyServer 的 sent(channel, msg)，但是 NettyServer 没有该方法，因此会调用父类 AbstractPeer.sent 方法
     * 2 AbstractPeer 中装饰的 ChannelHandler 最外层是 MultiMessageHandler
     * 3 MultiMessageHandler 想尝试调用 sent 发现没有该方法，于是找父类 AbstractChannelHandlerDelegate.sent 方法
     * 4 父类 AbstractChannelHandlerDelegate.sent 使用的是 MultiMessageHandler 装饰的 HeartbeatHandler ，此时就找到了
     *
     * @param handler
     * @param url
     * @return
     */
    protected ChannelHandler wrapInternal(ChannelHandler handler, URL url) {
        return new MultiMessageHandler( // MultiMessageHandler
                new HeartbeatHandler( // HeartbeatHandler
                        ExtensionLoader.getExtensionLoader(Dispatcher.class)
                                .getAdaptiveExtension() // AllDispatcher ,Dispatcher决定了dubbo的线程模型，指定了哪些线程做什么
                                .dispatch(handler, url) // 返回的也是一个 ChannelHandlerDelegate 类型的对象，默认是 AllChannelHandler
                )
        );
    }
}
