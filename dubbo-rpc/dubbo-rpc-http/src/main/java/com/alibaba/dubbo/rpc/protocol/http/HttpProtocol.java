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
package com.alibaba.dubbo.rpc.protocol.http;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.remoting.http.HttpBinder;
import com.alibaba.dubbo.remoting.http.HttpHandler;
import com.alibaba.dubbo.remoting.http.HttpServer;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.protocol.AbstractProxyProtocol;
import com.alibaba.dubbo.rpc.service.GenericService;
import com.alibaba.dubbo.rpc.support.ProtocolUtils;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.remoting.RemoteAccessException;
import org.springframework.remoting.httpinvoker.HttpComponentsHttpInvokerRequestExecutor;
import org.springframework.remoting.httpinvoker.HttpInvokerProxyFactoryBean;
import org.springframework.remoting.httpinvoker.HttpInvokerServiceExporter;
import org.springframework.remoting.httpinvoker.SimpleHttpInvokerRequestExecutor;
import org.springframework.remoting.support.RemoteInvocation;
import org.springframework.remoting.support.RemoteInvocationFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HttpProtocol，继承AbstractProxyProtocol 抽象类
 */
public class HttpProtocol extends AbstractProxyProtocol {
    /**
     * 默认服务器端口
     */
    public static final int DEFAULT_PORT = 80;

    /**
     * Http 服务器集合
     * key: ip:port
     * value: Http服务器
     */
    private final Map<String, HttpServer> serverMap = new ConcurrentHashMap<String, HttpServer>();

    /**
     * Spring 的 HttpInvokerServiceExporter 集合
     * key: /path 服务名
     * value: spring的HttpInvokerServiceExporter
     * 请求处理过程说明：
     * HttpServer -> DispatcherServlet -> InternalHandler -> HttpInvokerServiceExporter -> 具体服务
     */
    private final Map<String, HttpInvokerServiceExporter> skeletonMap = new ConcurrentHashMap<String, HttpInvokerServiceExporter>();

    /**
     * HttpBinder$Adaptive 对象,通过 {@link #setHttpBinder(HttpBinder)}方法，Dubbo SPI IOC注入
     */
    private HttpBinder httpBinder;

    public HttpProtocol() {
        super(RemoteAccessException.class);
    }

    public void setHttpBinder(HttpBinder httpBinder) {
        this.httpBinder = httpBinder;
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    @Override
    protected <T> Runnable doExport(final T impl, Class<T> type, URL url) throws RpcException {
        // 1 获取服务器地址 ip:port
        String addr = getAddr(url);
        // 2 根据地址从缓存中获得 HttpServer 对象，若不存在，进行创建
        HttpServer server = serverMap.get(addr);
        if (server == null) {
            /**
             * 1 通过SPI机制获取具体的 HttpBinder的拓展实现
             * 2 具体的HttpBinder实现调用bind方法：
             *   1）启动服务
             *   2）为服务设置请求处理器(InternalHandler对象)
             *   3)接收到请求后，InternalHandler 就会将请求交给 Export 处理，具体是 Export 封装的 impl
             * 3 创建 Servlet 容器，在设置 DispatcherServlet 时，会设置拦截的路径为 /* ，这样的请求下就会把代理请求的路径拦截下来，最终交给 InternalHandler
             */
            server = httpBinder.bind(url, new InternalHandler());
            // 将创建好的服务加入缓存
            serverMap.put(addr, server);
        }

        // todo 3 创建 Export 暴露器，负责处理请求，具体由 Export 绑定的 impl 处理

        // 3.1 获取url的path，以此为 key 缓存 HttpInvokerServiceExporter，如：/com.alibaba.dubbo.demo.DemoService
        final String path = url.getAbsolutePath();
        skeletonMap.put(path, createExporter(impl, type));

        // 3.2  支持泛化，只需将服务实现的接口替换成泛化接口 GenericService 即可，如：/com.alibaba.dubbo.demo.DemoService/generic
        final String genericPath = path + "/" + Constants.GENERIC_KEY;
        skeletonMap.put(genericPath, createExporter(impl, GenericService.class));

        // 4 返回取消暴露的回调 Runnable
        return new Runnable() {
            /**
             * 在回调时会移除对应的缓存 HttpInvokerServiceExporter
             */
            @Override
            public void run() {
                skeletonMap.remove(path);
                skeletonMap.remove(genericPath);
            }
        };
    }

    /**
     * 创建Exporter（使用Spring的）
     * 注意：
     * HttpInvoker 默认使用的序列化为 application/x-java-serialized-object ，即 Java 序列方式。我们也可以设置序列方式：
     * httpServiceExporter.setContentType(xxx);
     * application/json,
     * application/x-www-form-urlencoded,
     * application/json, application/*+json, application/json, application/*+json, application/json, application/x-www-form-urlencoded]
     *
     * @param impl
     * @param type
     * @param <T>
     * @return
     */
    private <T> HttpInvokerServiceExporter createExporter(T impl, Class<?> type) {
        //  1 创建 HttpInvokerServiceExporter，以 serviceInterface 为公共接口，以 service 为实现类向外提供服务。
        final HttpInvokerServiceExporter httpServiceExporter = new HttpInvokerServiceExporter();
        // 2 设置接口
        httpServiceExporter.setServiceInterface(type);
        // 3 设置实现
        httpServiceExporter.setService(impl);

        // 可设置序列化方式
        //httpServiceExporter.setContentType("application/json");
        try {
            // 4 根据接口和实现，创建代理对象（Spring实现的），是 HttpInvokerServiceExporter 中的一个属性。
            httpServiceExporter.afterPropertiesSet();
        } catch (Exception e) {
            throw new RpcException(e.getMessage(), e);
        }
        return httpServiceExporter;
    }

    /**
     * todo ? 怎么和服务暴露挂钩的？？？？
     * 基于 HttpClient 作为通信客户端。具体的 RPC 调用的实现在父类 {@link #refer(Class, URL)} 方法中
     *
     * @param serviceType
     * @param url         URL
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    @SuppressWarnings("unchecked")
    protected <T> T doRefer(final Class<T> serviceType, final URL url) throws RpcException {
        // 1 判断是否是泛化调用
        final String generic = url.getParameter(Constants.GENERIC_KEY);
        final boolean isGeneric = ProtocolUtils.isGeneric(generic) || serviceType.equals(GenericService.class);

        // 2 创建 HttpInvokerProxyFactoryBean 对象
        // Spring 封装的一个服务引用器，serviceInterface 指定了生成代理的接口，serviceUrl 指定了服务所在的地址（与服务暴露者的路径需要对应）
        final HttpInvokerProxyFactoryBean httpProxyFactoryBean = new HttpInvokerProxyFactoryBean();

        // 3 设置远程调用信息，其中包括对附加属性和泛化调用的处理
        httpProxyFactoryBean.setRemoteInvocationFactory(new RemoteInvocationFactory() {
            @Override
            public RemoteInvocation createRemoteInvocation(MethodInvocation methodInvocation) {
                RemoteInvocation invocation = new HttpRemoteInvocation(methodInvocation);
                if (isGeneric) {
                    invocation.addAttribute(Constants.GENERIC_KEY, generic);
                }
                return invocation;
            }
        });

        // todo 4 基于服务 path 设置目标服务的调用地址 ，如：http://10.1.31.48:8080/com.alibaba.dubbo.demo.DemoService
        // 每个服务对应一个独有的 http 请求路径
        String key = url.toIdentityString();
        // 4.1 调用泛化服务 如：http://10.1.31.48:8080/com.alibaba.dubbo.demo.DemoService/generic
        if (isGeneric) {
            key = key + "/" + Constants.GENERIC_KEY;
        }
        // 4.2 设置访问服务路径，格式：ip:port/path
        httpProxyFactoryBean.setServiceUrl(key);
        // 4.3 设置生成代理的接口
        httpProxyFactoryBean.setServiceInterface(serviceType);

        // 5 初始化客户端类型 client 参数
        String client = url.getParameter(Constants.CLIENT_KEY);

        // 根据客户端类型不同，创建不同的 执行器，默认创建 SimpleHttpInvokerRequestExecutor 对象，即使用的是 JDK 的 HTTP 功能。
        // 以下两种方式 Content-Type 的值为 application/x-java-serialized-object，即使用的序列化方式为 java 序列化
        if (client == null || client.length() == 0 || "simple".equals(client)) {
            // 使用的HttpClient 是 JDK HttpClent
            SimpleHttpInvokerRequestExecutor httpInvokerRequestExecutor = new SimpleHttpInvokerRequestExecutor() {
                @Override
                protected void prepareConnection(HttpURLConnection con,
                                                 int contentLength) throws IOException {
                    super.prepareConnection(con, contentLength);
                    con.setReadTimeout(url.getParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT));
                    con.setConnectTimeout(url.getParameter(Constants.CONNECT_TIMEOUT_KEY, Constants.DEFAULT_CONNECT_TIMEOUT));
                }
            };

            // 可设置序列化
           // httpInvokerRequestExecutor.setContentType("application/json");

            // todo 创建代理对象使用的 client
            httpProxyFactoryBean.setHttpInvokerRequestExecutor(httpInvokerRequestExecutor);
        } else if ("commons".equals(client)) {

            // 使用的HttpClient 是 Apache HttpClient
            HttpComponentsHttpInvokerRequestExecutor httpInvokerRequestExecutor = new HttpComponentsHttpInvokerRequestExecutor();
            httpInvokerRequestExecutor.setReadTimeout(url.getParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT));
            httpInvokerRequestExecutor.setConnectTimeout(url.getParameter(Constants.CONNECT_TIMEOUT_KEY, Constants.DEFAULT_CONNECT_TIMEOUT));

            // 可设置序列化
          //  httpInvokerRequestExecutor.setContentType("application/json");

            // todo 创建代理对象使用的 client
            httpProxyFactoryBean.setHttpInvokerRequestExecutor(httpInvokerRequestExecutor);
        } else {
            throw new IllegalStateException("Unsupported http protocol client " + client + ", only supported: simple, commons");
        }


        // 6 返回指定接口的代理对象
        httpProxyFactoryBean.afterPropertiesSet();
        return (T) httpProxyFactoryBean.getObject();
    }

    @Override
    protected int getErrorCode(Throwable e) {
        if (e instanceof RemoteAccessException) {
            e = e.getCause();
        }
        if (e != null) {
            Class<?> cls = e.getClass();
            if (SocketTimeoutException.class.equals(cls)) {
                return RpcException.TIMEOUT_EXCEPTION;
            } else if (IOException.class.isAssignableFrom(cls)) {
                return RpcException.NETWORK_EXCEPTION;
            } else if (ClassNotFoundException.class.isAssignableFrom(cls)) {
                return RpcException.SERIALIZATION_EXCEPTION;
            }
        }
        return super.getErrorCode(e);
    }

    /**
     * 接收请求处理器
     */
    private class InternalHandler implements HttpHandler {
        /**
         * 处理请求
         *
         * @param request  request 请求
         * @param response response 响应
         * @throws IOException
         * @throws ServletException
         */
        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException {

            /**
             * todo 1 获取请求的uri
             * @see HttpProtocol#doRefer(java.lang.Class, com.alibaba.dubbo.common.URL) 服务引用时已经确定代理对象请求的路径了
             */
            String uri = request.getRequestURI();
            // 2 从缓存中取出uri对应的 HttpInvokerServiceExporter 对象
            HttpInvokerServiceExporter skeleton = skeletonMap.get(uri);

            // 3 必须是post请求（ Dubbo 2.6.x 的 http 协议是基于HTTP表单的远程调用协议)
            if (!request.getMethod().equalsIgnoreCase("POST")) {
                // 不是post请求就直接返回500
                response.setStatus(500);
            } else {
                // 4 设置远程调用地址
                RpcContext.getContext().setRemoteAddress(request.getRemoteAddr(), request.getRemotePort());
                try {
                    // 5 处理请求，结果写到response中
                    // 响应数据类型使用的也是 application/x-java-serialized-object
                    skeleton.handleRequest(request, response);
                } catch (Throwable e) {
                    throw new ServletException(e);
                }
            }
        }
    }

}
