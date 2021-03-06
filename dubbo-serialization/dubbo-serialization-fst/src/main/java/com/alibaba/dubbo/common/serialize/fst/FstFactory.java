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
package com.alibaba.dubbo.common.serialize.fst;

import com.alibaba.dubbo.common.serialize.support.SerializableClassRegistry;

import org.nustaq.serialization.FSTConfiguration;
import org.nustaq.serialization.FSTObjectInput;
import org.nustaq.serialization.FSTObjectOutput;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * fast-serialization 工厂
 */
public class FstFactory {

    /**
     * 单例
     */
    private static final FstFactory factory = new FstFactory();

    /**
     * 配置对象
     */
    private final FSTConfiguration conf = FSTConfiguration.createDefaultConfiguration();


    /**
     * 静态方法，获取默认工厂
     *
     * @return
     */
    public static FstFactory getDefaultFactory() {
        return factory;
    }

    public FstFactory() {
        // 将 要序列化优化的类 注册到 FSTConfiguration 配置对象中
        for (Class clazz : SerializableClassRegistry.getRegisteredClasses()) {
            conf.registerClass(clazz);
        }
    }

    /**
     * 获得 FSTObjectOutput 对象
     *
     * @param outputStream
     * @return
     */
    public FSTObjectOutput getObjectOutput(OutputStream outputStream) {
        return conf.getObjectOutput(outputStream);
    }

    /**
     * 获得 FSTObjectInput 对象
     *
     * @param inputStream
     * @return
     */
    public FSTObjectInput getObjectInput(InputStream inputStream) {
        return conf.getObjectInput(inputStream);
    }
}
