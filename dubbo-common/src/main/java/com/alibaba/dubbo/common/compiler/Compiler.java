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
package com.alibaba.dubbo.common.compiler;


import com.alibaba.dubbo.common.extension.SPI;

/**
 * Compiler. (SPI, Singleton, ThreadSafe)
 * 使用Dubbo SPI机制，默认拓展名为Javassist
 */
@SPI("javassist")
public interface Compiler {

    /**
     * 编译Java 代码
     *
     * @param code        Java代码字符串
     * @param classLoader 类加载器
     * @return Compiled class 编译后的类
     */
    Class<?> compile(String code, ClassLoader classLoader);

}
