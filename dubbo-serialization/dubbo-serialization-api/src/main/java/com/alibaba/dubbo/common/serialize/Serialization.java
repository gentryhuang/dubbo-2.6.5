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
package com.alibaba.dubbo.common.serialize;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Serialization. (SPI, Singleton, ThreadSafe)
 * 序列化接口，@SPI("hessian2) 注解，Dubbo SPI拓展点，默认为 hessian2 ，即在未配置情况下使用Hessian 进行序列化和反序列化
 */
@SPI("hessian2")
public interface Serialization {

    /**
     * 获得内容类型编号
     *
     * @return content type id
     */
    byte getContentTypeId();

    /**
     * 获得内容类型名
     *
     * @return content type
     */
    String getContentType();

    /**
     * create serializer
     * <p>
     * 创建ObjectOutput对象，序列化输出到 OutputStream
     *
     * @param url    URL
     * @param output 输出流
     * @return serializer
     * @throws IOException
     */
    @Adaptive
    ObjectOutput serialize(URL url, OutputStream output) throws IOException;

    /**
     * create deserializer
     * <p>
     * 创建 ObjectInput 对象，从 InputStream 反序列化
     *
     * @param url   URL
     * @param input 输入流
     * @return deserializer
     * @throws IOException
     */
    @Adaptive
    ObjectInput deserialize(URL url, InputStream input) throws IOException;

}