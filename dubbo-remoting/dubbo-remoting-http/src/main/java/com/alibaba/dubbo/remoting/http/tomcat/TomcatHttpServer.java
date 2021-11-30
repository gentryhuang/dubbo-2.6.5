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
package com.alibaba.dubbo.remoting.http.tomcat;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.http.HttpHandler;
import com.alibaba.dubbo.remoting.http.servlet.DispatcherServlet;
import com.alibaba.dubbo.remoting.http.servlet.ServletManager;
import com.alibaba.dubbo.remoting.http.support.AbstractHttpServer;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;

import java.io.File;

/**
 * 实现 AbstractHttpServer 抽象类，基于Tomcat 的 HTTP服务器实现类
 */
public class TomcatHttpServer extends AbstractHttpServer {

    private static final Logger logger = LoggerFactory.getLogger(TomcatHttpServer.class);

    /**
     * 内嵌Tomcat
     */
    private final Tomcat tomcat;

    /**
     * URL 对象
     */
    private final URL url;

    public TomcatHttpServer(URL url, final HttpHandler handler) {
        super(url, handler);
        this.url = url;
        // 1 注册请求处理器HttpHandler 到 DispatcherServlet的 处理器集合中
        DispatcherServlet.addHttpHandler(url.getPort(), handler);
        // 2 创建内嵌Tomcat
        String baseDir = new File(System.getProperty("java.io.tmpdir")).getAbsolutePath();
        tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir);
        // 设置端口
        tomcat.setPort(url.getPort());
        // 设置最大线程数
        tomcat.getConnector().setProperty("maxThreads", String.valueOf(url.getParameter(Constants.THREADS_KEY, Constants.DEFAULT_THREADS)));

//        tomcat.getConnector().setProperty(
//                "minSpareThreads", String.valueOf(url.getParameter(Constants.THREADS_KEY, Constants.DEFAULT_THREADS)));

        // 设置最大连接池
        tomcat.getConnector().setProperty("maxConnections", String.valueOf(url.getParameter(Constants.ACCEPTS_KEY, -1)));
        // 编码为UTF-8
        tomcat.getConnector().setProperty("URIEncoding", "UTF-8");
        // 连接超时 60 秒
        tomcat.getConnector().setProperty("connectionTimeout", "60000");
        tomcat.getConnector().setProperty("maxKeepAliveRequests", "-1");
        tomcat.getConnector().setProtocol("org.apache.coyote.http11.Http11NioProtocol");

        // 3 创建并添加DispatcherServlet 到 Tomcat 中，作为 Tomcat 的处理器分发器
        Context context = tomcat.addContext("/", baseDir);


        // 创建容器的派发器，并设置拦截路径
        Tomcat.addServlet(context, "dispatcher", new DispatcherServlet());
        // todo 拦截所有请求
        context.addServletMapping("/*", "dispatcher");

        // 4 添加ServletContext 上下文对象 到 ServletManager 中
        ServletManager.getInstance().addServletContext(url.getPort(), context.getServletContext());

        try {
            // 5 启动tomcat
            tomcat.start();
        } catch (LifecycleException e) {
            throw new IllegalStateException("Failed to start tomcat server at " + url.getAddress(), e);
        }
    }

    /**
     * 关闭
     * 说明：这里最好调用 {@link DispatcherServlet#removeHttpHandler(int)} 方法，将HttpHandler对象移除
     */
    @Override
    public void close() {
        // 标记关闭
        super.close();
        // 移除 ServletContext 对象
        ServletManager.getInstance().removeServletContext(url.getPort());
        // 关闭tomcat
        try {
            tomcat.stop();
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
        }
    }
}
