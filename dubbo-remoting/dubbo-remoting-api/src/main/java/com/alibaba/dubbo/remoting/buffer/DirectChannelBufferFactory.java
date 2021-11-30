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

package com.alibaba.dubbo.remoting.buffer;

import java.nio.ByteBuffer;

/**
 * 实现 ChannelBufferFactory 接口，创建 ByteBufferBackedChannelBuffer 的工厂
 */
public class DirectChannelBufferFactory implements ChannelBufferFactory {

    /**
     * 单例
     */
    private static final DirectChannelBufferFactory INSTANCE = new DirectChannelBufferFactory();

    public DirectChannelBufferFactory() {
        super();
    }

    public static ChannelBufferFactory getInstance() {
        return INSTANCE;
    }

    @Override
    public ChannelBuffer getBuffer(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity: " + capacity);
        }
        if (capacity == 0) {
            return ChannelBuffers.EMPTY_BUFFER;
        }
        // 使用 ChannelBuffers 工具类创建 ByteBufferBackedChannelBuffer
        return ChannelBuffers.directBuffer(capacity);
    }

    @Override
    public ChannelBuffer getBuffer(byte[] array, int offset, int length) {
        if (array == null) {
            throw new NullPointerException("array");
        }
        if (offset < 0) {
            throw new IndexOutOfBoundsException("offset: " + offset);
        }
        if (length == 0) {
            return ChannelBuffers.EMPTY_BUFFER;
        }
        if (offset + length > array.length) {
            throw new IndexOutOfBoundsException("length: " + length);
        }
        // 调用 getBuffer 方法，使用 ChannelBuffers 工具类创建 ByteBufferBackedChannelBuffer
        ChannelBuffer buf = getBuffer(length);
        // 向 buf 中写入数据
        buf.writeBytes(array, offset, length);
        return buf;
    }

    @Override
    public ChannelBuffer getBuffer(ByteBuffer nioBuffer) {
        if (!nioBuffer.isReadOnly() && nioBuffer.isDirect()) {
            return ChannelBuffers.wrappedBuffer(nioBuffer);
        }

        // 调用 getBuffer 方法，使用 ChannelBuffers 工具类创建 ByteBufferBackedChannelBuffer
        ChannelBuffer buf = getBuffer(nioBuffer.remaining());
        int pos = nioBuffer.position();
        // 向buf中写入数据
        buf.writeBytes(nioBuffer);
        nioBuffer.position(pos);
        return buf;
    }

}
