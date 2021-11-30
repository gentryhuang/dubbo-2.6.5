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
package com.alibaba.dubbo.rpc.protocol.dubbo;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.serialize.Cleanable;
import com.alibaba.dubbo.common.serialize.ObjectInput;
import com.alibaba.dubbo.common.utils.Assert;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.Codec;
import com.alibaba.dubbo.remoting.Decodeable;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.transport.CodecSupport;
import com.alibaba.dubbo.rpc.RpcInvocation;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import static com.alibaba.dubbo.rpc.protocol.dubbo.CallbackServiceCodec.decodeInvocationArgument;

/**
 * 可解码的 RpcInvocation 实现类，它是用来支持解码的
 */
public class DecodeableRpcInvocation extends RpcInvocation implements Codec, Decodeable {

    private static final Logger log = LoggerFactory.getLogger(DecodeableRpcInvocation.class);

    /**
     * Dubbo 的通道
     */
    private Channel channel;
    /**
     * Serialization 类型编号
     */
    private byte serializationType;
    /**
     * 字节流
     */
    private InputStream inputStream;
    /**
     * 请求
     */
    private Request request;
    /**
     * 是否已经解码完成
     */
    private volatile boolean hasDecoded;

    /**
     * 可解码 Invocation
     *
     * @param channel Dubbo 底层通道
     * @param request 请求
     * @param is      字节流消息
     * @param id      序列化编号
     */
    public DecodeableRpcInvocation(Channel channel, Request request, InputStream is, byte id) {
        Assert.notNull(channel, "channel == null");
        Assert.notNull(request, "request == null");
        Assert.notNull(is, "inputStream == null");
        this.channel = channel;
        this.request = request;
        this.inputStream = is;
        this.serializationType = id;
    }

    /**
     * 解码
     *
     * @throws Exception
     */
    @Override
    public void decode() throws Exception {
        if (!hasDecoded && channel != null && inputStream != null) {
            try {
                decode(channel, inputStream);
            } catch (Throwable e) {
                if (log.isWarnEnabled()) {
                    log.warn("Decode rpc invocation failed: " + e.getMessage(), e);
                }

                // 解码失败，设置失败标志
                request.setBroken(true);
                request.setData(e);
            } finally {
                hasDecoded = true;
            }
        }
    }

    @Override
    public void encode(Channel channel, OutputStream output, Object message) throws IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * 过反序列化将诸如 path、version、调用方法名、参数列表等信息依次解析出来，并设置到相应的字段中，最终得到一个具有完整调用信息的 DecodeableRpcInvocation 对象。
     * todo 编码的时候就是将 RpcInvocation 中的信息写入到流中，这里执行相反的操作，将流中的数据读取出来放入到 RpcInvocation 中
     *
     * @param channel channel.
     * @param input   input stream.
     * @return
     * @throws IOException
     */
    @Override
    public Object decode(Channel channel, InputStream input) throws IOException {

        // 获取序列化方式，然后通过反序列化得到所需的调用信息
        ObjectInput in = CodecSupport.getSerialization(channel.getUrl(), serializationType).deserialize(channel.getUrl(), input);

        /** 和编码设置的参数对应，写什么读什么 */

        // 通过反序列化得到框架版本
        String dubboVersion = in.readUTF();
        request.setVersion(dubboVersion);

        // 通过反序列化得到 `dubbo` `path` `version`，并保存到 attachments 变量中
        setAttachment(Constants.DUBBO_VERSION_KEY, dubboVersion);
        setAttachment(Constants.PATH_KEY, in.readUTF()); // 读取调用接口
        setAttachment(Constants.VERSION_KEY, in.readUTF()); // 读取接口指定的版本，默认为 0.0.0

        // 通过反序列化得到调用方法名
        setMethodName(in.readUTF());

        try {
            // 参数列表
            Object[] args;
            // 参数类型列表
            Class<?>[] pts;

            // 通过反序列化得到参数类型字符串，如： Ljava/lang/String
            String desc = in.readUTF();

            if (desc.length() == 0) {
                pts = DubboCodec.EMPTY_CLASS_ARRAY;
                args = DubboCodec.EMPTY_OBJECT_ARRAY;
            } else {
                // 将 desc 解析为参数类型数组
                pts = ReflectUtils.desc2classArray(desc);
                args = new Object[pts.length];

                // 一次读取方法参数值
                for (int i = 0; i < args.length; i++) {
                    try {
                        // 解析运行时参数
                        args[i] = in.readObject(pts[i]);
                    } catch (Exception e) {
                        if (log.isWarnEnabled()) {
                            log.warn("Decode argument failed: " + e.getMessage(), e);
                        }
                    }
                }
            }

            // 设置参数类型数组
            setParameterTypes(pts);

            // 通过反序列化得到原 attachments 的内容，即隐式参数
            Map<String, String> map = (Map<String, String>) in.readObject(Map.class);
            if (map != null && map.size() > 0) {
                Map<String, String> attachment = getAttachments();
                if (attachment == null) {
                    attachment = new HashMap<String, String>();
                }

                // 将 原 attachments 的内容融合到 当前对象的 attachment 中
                attachment.putAll(map);
                setAttachments(attachment);
            }

            // 进一步解码方法参数，主要为了参数返回
            for (int i = 0; i < args.length; i++) {
                args[i] = decodeInvocationArgument(channel, this, pts, i, args[i]);
            }

            // 设置参数列表
            setArguments(args);

        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read invocation data failed.", e));
        } finally {
            if (in instanceof Cleanable) {
                ((Cleanable) in).cleanup();
            }
        }
        return this;
    }

}
