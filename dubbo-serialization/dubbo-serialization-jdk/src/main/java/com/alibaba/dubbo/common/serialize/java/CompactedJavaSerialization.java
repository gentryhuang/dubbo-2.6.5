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
package com.alibaba.dubbo.common.serialize.java;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.serialize.ObjectInput;
import com.alibaba.dubbo.common.serialize.ObjectOutput;
import com.alibaba.dubbo.common.serialize.Serialization;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 在Java序列化基础上，实现了对 ClassDescriptor 的处理
 */
public class CompactedJavaSerialization implements Serialization {

    @Override
    public byte getContentTypeId() {
        return 4;
    }

    @Override
    public String getContentType() {
        return "x-application/compactedjava";
    }

    /**
     * 在创建 JavaObjectOutput 时，根据 compact = true 时，使用 CompactedObjectOutputStream 输出流
     *
     * @param url URL
     * @param out
     * @return
     * @throws IOException
     */
    @Override
    public ObjectOutput serialize(URL url, OutputStream out) throws IOException {
        return new JavaObjectOutput(out, true);
    }

    @Override
    public ObjectInput deserialize(URL url, InputStream is) throws IOException {
        return new JavaObjectInput(is, true);
    }

}
