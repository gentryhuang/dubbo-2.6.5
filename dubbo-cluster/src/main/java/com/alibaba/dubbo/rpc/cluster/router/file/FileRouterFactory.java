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
package com.alibaba.dubbo.rpc.cluster.router.file;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.IOUtils;
import com.alibaba.dubbo.rpc.cluster.Router;
import com.alibaba.dubbo.rpc.cluster.RouterFactory;
import com.alibaba.dubbo.rpc.cluster.router.script.ScriptRouterFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * 实现RouterFactory，基于文件读取路由规则
 */
public class FileRouterFactory implements RouterFactory {

    /**
     * 拓展名
     */
    public static final String NAME = "file";

    /**
     * RouterFactory$Adaptive 对象, Dubbo IOC注入
     */
    private RouterFactory routerFactory;

    public void setRouterFactory(RouterFactory routerFactory) {
        this.routerFactory = routerFactory;
    }

    @Override
    public Router getRouter(URL url) {
        try {

            // router 配置项，默认为 script

            // 将 file 协议的 URL 转换成 script 协议的 URL
            // file:///d:/path/to/route.js?router=script ==> script:///d:/path/to/route.js?type=js&rule=<file-content>

            // 1 获取 router 配置项，默认为 script
            // Replace original protocol (maybe 'file') with 'script'
            String protocol = url.getParameter(Constants.ROUTER_KEY, ScriptRouterFactory.NAME);

            // 使用文件后缀作为类型，如：js、groovy
            String type = null;

            // 2 获取path
            String path = url.getPath();

            // 3 获取脚本文件的语言类型
            if (path != null) {
                int i = path.lastIndexOf('.');
                if (i > 0) {
                    type = path.substring(i + 1);
                }
            }

            // 4 从文件中读取路由规则，作为 ScriptRouter 的路由规则
            String rule = IOUtils.read(new FileReader(new File(url.getAbsolutePath())));

            // 5 创建script协议的URL
            boolean runtime = url.getParameter(Constants.RUNTIME_KEY, false);
            // 5.1 protocol 决定使用哪种路由，默认为script
            URL script = url.setProtocol(protocol)
                    // 5.2 type
                    .addParameter(Constants.TYPE_KEY, type)
                    // 5.3 runtime
                    .addParameter(Constants.RUNTIME_KEY, runtime)
                    // 5.4 路由规则 rule
                    .addParameterAndEncoded(Constants.RULE_KEY, rule);

            // 获取script对应的Router实现
            return routerFactory.getRouter(script);

        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

}
