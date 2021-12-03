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
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.serialize.support.SerializableClassRegistry;
import com.alibaba.dubbo.common.serialize.support.SerializationOptimizer;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.Transporter;
import com.alibaba.dubbo.remoting.exchange.ExchangeChannel;
import com.alibaba.dubbo.remoting.exchange.ExchangeClient;
import com.alibaba.dubbo.remoting.exchange.ExchangeHandler;
import com.alibaba.dubbo.remoting.exchange.ExchangeServer;
import com.alibaba.dubbo.remoting.exchange.Exchangers;
import com.alibaba.dubbo.remoting.exchange.support.ExchangeHandlerAdapter;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.protocol.AbstractProtocol;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * dubbo protocol support.
 */
public class DubboProtocol extends AbstractProtocol {
    /**
     * 协议名
     */
    public static final String NAME = "dubbo";
    /**
     * 协议默认端口
     */
    public static final int DEFAULT_PORT = 20880;
    /**
     * 参数回调用相关字段
     */
    private static final String IS_CALLBACK_SERVICE_INVOKE = "_isCallBackServiceInvoke";
    /**
     * DubboProtocol
     */
    private static DubboProtocol INSTANCE;
    /**
     * 通信服务集合
     * key: 服务器地址。格式：host:port
     * value: ExchangeServer 信息交换服务接口
     */
    private final Map<String, ExchangeServer> serverMap = new ConcurrentHashMap<String, ExchangeServer>();

    /**
     * 通信连接集合
     * key: 服务器地址 格式：host:port
     * value: 客户端
     */
    private final Map<String, ReferenceCountExchangeClient> referenceClientMap = new ConcurrentHashMap<String, ReferenceCountExchangeClient>();
    /**
     * 通信连接集合 - 延迟连接的创建
     * key: 服务器地址 格式:host:port
     * value: 客户端
     */
    private final ConcurrentMap<String, LazyConnectExchangeClient> ghostClientMap = new ConcurrentHashMap<String, LazyConnectExchangeClient>();
    /**
     * 用于jvm 锁集合
     */
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<String, Object>();

    /**
     * 已初始化的 SerializationOptimizer 实现类名的集合
     * 用于序列化优化
     */
    private final Set<String> optimizers = new ConcurrentHashSet<String>();

    //consumer side export a stub service for dispatching event
    //servicekey-stubmethods
    private final ConcurrentMap<String, String> stubServiceMethodsMap = new ConcurrentHashMap<String, String>();

    public DubboProtocol() {
        INSTANCE = this;
    }

    public static DubboProtocol getDubboProtocol() {
        if (INSTANCE == null) {
            // dubbo SPI 获取 DubboProtocl
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(DubboProtocol.NAME);
        }
        return INSTANCE;
    }


    /**
     * 经过层层包装后，会成为最终的服务端处理器。该处理器负责将请求转发到对应的Invoker对象，执行逻辑，返回结果。
     * 说明：
     * 在 DubboProtocol 类中，实现了自己的 ExchangeHandler 对象，处理请求、消息、连接、断开连接等事件，
     * 对于服务消费者的远程调用，通过 #reply(ExchangeChannel channel, Object message) 和 #reply(Channel channel, Object message) 方法来处理
     */
    private ExchangeHandler requestHandler = new ExchangeHandlerAdapter() {

        /**
         * 处理服务消费者的 同步调用和异步调用的请求
         *
         * @param channel
         * @param message
         * @return
         * @throws RemotingException
         */
        @Override
        public Object reply(ExchangeChannel channel, Object message) throws RemotingException {
            // 判断消息类型。其实，经过前面的 Hander 处理后这里收到的 Message 必须是 Invocation 类型的对象
            if (message instanceof Invocation) {
                Invocation inv = (Invocation) message;
                /**
                 * 获取此次调用的Invoker：
                 * 1 先获取 Exporter （在服务暴露时就已经初始化好了）
                 * 2 从 exporter 中获取 Invoker
                 */
                Invoker<?> invoker = getInvoker(channel, inv);
                /**
                 * 如果是参数回调：
                 * 1 需要处理高版本调用低版本的问题
                 * 2 校验服务消费者实际存在对应的回调方法，通过方法名判断
                 */
                if (Boolean.TRUE.toString().equals(inv.getAttachments().get(IS_CALLBACK_SERVICE_INVOKE))) {
                    String methodsStr = invoker.getUrl().getParameters().get("methods");
                    boolean hasMethod = false;
                    if (methodsStr == null || methodsStr.indexOf(",") == -1) {
                        hasMethod = inv.getMethodName().equals(methodsStr);
                    } else {
                        String[] methods = methodsStr.split(",");
                        for (String method : methods) {
                            if (inv.getMethodName().equals(method)) {
                                hasMethod = true;
                                break;
                            }
                        }
                    }
                    if (!hasMethod) {
                        logger.warn(new IllegalStateException("The methodName " + inv.getMethodName()
                                + " not found in callback service interface ,invoke will be ignored."
                                + " please update the api interface. url is:"
                                + invoker.getUrl()) + " ,invocation is :" + inv);
                        return null;
                    }
                }

                // 设置调用方的地址，即将客户端的地址记录到 RpcContext 中
                RpcContext.getContext().setRemoteAddress(channel.getRemoteAddress());
                /**
                 *  执行调用
                 *  1 执行 Filter链 ：EchoFilter->ClassLoaderFilter->GenericFilter->ContextFilter->TraceFilter->TimeoutFilter->MonitorFilter->ExceptionFilter -> Invoker逻辑
                 *  2 然后执行真正的Invoker的调用逻辑：AbstractProxyInvoker.invoke -> JavassistProxyFactory$AbstractProxyInvoker.doInvoke -> Wrapper.invokeMethod(proxy, methodName, parameterTypes, arguments) -> ref.xxxYYY方法
                 */
                return invoker.invoke(inv);
            }
            throw new RemotingException(channel, "Unsupported request: "
                    + (message == null ? null : (message.getClass().getName() + ": " + message))
                    + ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress());
        }

        /**
         *处理读取到的数据
         *
         * @param channel
         * @param message
         * @throws RemotingException
         */
        @Override
        public void received(Channel channel, Object message) throws RemotingException {
            // 判断消息类型是不是 Invocation
            if (message instanceof Invocation) {
                reply((ExchangeChannel) channel, message);
            } else {
                super.received(channel, message);
            }
        }

        /**
         * 在服务提供者上可以配置 onconnect 配置项指定连接上服务时会调用的方法
         * @param channel
         * @throws RemotingException
         */
        @Override
        public void connected(Channel channel) throws RemotingException {
            invoke(channel, Constants.ON_CONNECT_KEY);
        }

        /**
         * 在服务提供者上可以配置 'ondisconnect' 配置项指定方法，在服务提供者连接断开时会调用该方法。
         *
         * @param channel
         * @throws RemotingException
         */
        @Override
        public void disconnected(Channel channel) throws RemotingException {
            if (logger.isInfoEnabled()) {
                logger.info("disconnected from " + channel.getRemoteAddress() + ",url:" + channel.getUrl());
            }
            invoke(channel, Constants.ON_DISCONNECT_KEY);
        }

        /**
         * 进行调用，执行对应的方法
         *
         * @param channel 通道
         * @param methodKey 方法名
         */
        private void invoke(Channel channel, String methodKey) {
            // 创建调用信息 Invocation 对象
            Invocation invocation = createInvocation(channel, channel.getUrl(), methodKey);
            // 如果 invocation 不为空，执行received方法
            if (invocation != null) {
                try {
                    received(channel, invocation);
                } catch (Throwable t) {
                    logger.warn("Failed to invoke event method " + invocation.getMethodName() + "(), cause: " + t.getMessage(), t);
                }
            }
        }

        /**
         * 创建 Invocation
         * @param channel
         * @param url
         * @param methodKey
         * @return
         */
        private Invocation createInvocation(Channel channel, URL url, String methodKey) {
            String method = url.getParameter(methodKey);
            if (method == null || method.length() == 0) {
                return null;
            }
            RpcInvocation invocation = new RpcInvocation(method, new Class<?>[0], new Object[0]);
            invocation.setAttachment(Constants.PATH_KEY, url.getPath());
            invocation.setAttachment(Constants.GROUP_KEY, url.getParameter(Constants.GROUP_KEY));
            invocation.setAttachment(Constants.INTERFACE_KEY, url.getParameter(Constants.INTERFACE_KEY));
            invocation.setAttachment(Constants.VERSION_KEY, url.getParameter(Constants.VERSION_KEY));
            if (url.getParameter(Constants.STUB_EVENT_KEY, false)) {
                invocation.setAttachment(Constants.STUB_EVENT_KEY, Boolean.TRUE.toString());
            }
            return invocation;
        }
    };


    public Collection<ExchangeServer> getServers() {
        return Collections.unmodifiableCollection(serverMap.values());
    }

    public Collection<Exporter<?>> getExporters() {
        return Collections.unmodifiableCollection(exporterMap.values());
    }

    Map<String, Exporter<?>> getExporterMap() {
        return exporterMap;
    }

    private boolean isClientSide(Channel channel) {
        InetSocketAddress address = channel.getRemoteAddress();
        URL url = channel.getUrl();
        return url.getPort() == address.getPort() &&
                NetUtils.filterLocalHost(channel.getUrl().getIp())
                        .equals(NetUtils.filterLocalHost(address.getAddress().getHostAddress()));
    }

    /**
     * 获得请求对应的Invoker对象。即 根据 Invocation 携带的信息构造服务键，然后根据服务键从 exporterMap 缓存中查询对应的 DubboExporter 对象，并从中获取底层的Invoker对象
     *
     * @param channel
     * @param inv
     * @return
     * @throws RemotingException
     */
    Invoker<?> getInvoker(Channel channel, Invocation inv) throws RemotingException {
        boolean isCallBackServiceInvoke = false;
        boolean isStubServiceInvoke = false;

        // 获取端口
        int port = channel.getLocalAddress().getPort();
        // 从调用信息中国获取 path
        String path = inv.getAttachments().get(Constants.PATH_KEY);

        // 对客户端 Callback 的处理
        isStubServiceInvoke = Boolean.TRUE.toString().equals(inv.getAttachments().get(Constants.STUB_EVENT_KEY));
        if (isStubServiceInvoke) {
            // 从Channel中获取 端口 port
            port = channel.getRemoteAddress().getPort();
        }
        // 参数回调处理，获得真正的服务名 `path`
        isCallBackServiceInvoke = isClientSide(channel) && !isStubServiceInvoke;
        if (isCallBackServiceInvoke) {
            path = inv.getAttachments().get(Constants.PATH_KEY) + "." + inv.getAttachments().get(Constants.CALLBACK_SERVICE_KEY);
            inv.getAttachments().put(IS_CALLBACK_SERVICE_INVOKE, Boolean.TRUE.toString());
        }

        // 获得服务键，格式： group/path:version:port
        // 根据 Invocation 携带的信息：attachments 中的path、group、version以及从channel中获取的port 计算服务健
        String serviceKey = serviceKey(port, path, inv.getAttachments().get(Constants.VERSION_KEY), inv.getAttachments().get(Constants.GROUP_KEY));

        // 根据服务健查找缓存的 DubboExporter
        DubboExporter<?> exporter = (DubboExporter<?>) exporterMap.get(serviceKey);

        // 没有对应的 Exporter，直接抛出异常
        if (exporter == null) {
            throw new RemotingException(channel, "Not found exported service: " + serviceKey + " in " + exporterMap.keySet() + ", may be version or group mismatch " + ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress() + ", message:" + inv);
        }

        // 取出Exporter中的Invoker 对象
        return exporter.getInvoker();
    }

    public Collection<Invoker<?>> getInvokers() {
        return Collections.unmodifiableCollection(invokers);
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        // 1 获取URL，这个 invoker 是 InvokerDelegete ，其中的 URL 是服务提供者的URL，在使用 protocol.export 时，使用具体协议暴露服务
        URL url = invoker.getUrl();
        //2 服务暴露
        //2.1 获取服务键,如：demoGroup/com.alibaba.dubbo.demo.DemoService:1.0.0:20880
        String key = serviceKey(url);
        //2.2 将上层传入的 Invoker 对象封装成 DubboExporter 对象
        DubboExporter<T> exporter = new DubboExporter<T>(invoker, key, exporterMap);
        //2.3 缓存 DubboExporter 到父类AbstractProtocol Map缓存中,相同则覆盖
        exporterMap.put(key, exporter);

        //export an stub service for dispatching event 和本地存根有关
        Boolean isStubSupportEvent = url.getParameter(Constants.STUB_EVENT_KEY, Constants.DEFAULT_STUB_EVENT);
        // 参数回调相关
        Boolean isCallbackservice = url.getParameter(Constants.IS_CALLBACK_SERVICE, false);
        if (isStubSupportEvent && !isCallbackservice) {
            String stubServiceMethods = url.getParameter(Constants.STUB_EVENT_METHODS_KEY);
            if (stubServiceMethods == null || stubServiceMethods.length() == 0) {
                if (logger.isWarnEnabled()) {
                    logger.warn(new IllegalStateException("consumer [" + url.getParameter(Constants.INTERFACE_KEY) +
                            "], has set stubproxy support event ,but no stub methods founded."));
                }
            } else {
                stubServiceMethodsMap.put(url.getServiceKey(), stubServiceMethods);
            }
        }
        // 3 启动服务器
        openServer(url);
        // 4 优化序列化
        optimizeSerialization(url);
        // 5 将 Invoker 以 Exporter 形式暴露出去
        return exporter;
    }

    /**
     * 启动服务器
     *
     * @param url
     */
    private void openServer(URL url) {
        // 获取 host:port，并将其作为服务器实例缓存 key，用于标识当前的服务起实例
        String key = url.getAddress();
        // 参数配置项 isserver，只有Server端才能启动Server对象
        boolean isServer = url.getParameter(Constants.IS_SERVER_KEY, true);

        // 只有Server端才能启动Server对象
        if (isServer) {
            // 从serverMap缓存中获取服务器
            ExchangeServer server = serverMap.get(key);

            // 无 Server监听该地址
            if (server == null) {
                // 不存在则创建 Server
                serverMap.put(key, createServer(url));
            } else {
                // 如果已有 Server 实例，则尝试根据URL信息重置 Server
                // 存在则重置服务器属性 【同一台服务器同一个端口上 仅允许启动一个服务器实例。若某个端口上已有服务器实例，此时reset方法就会调用，重置服务器的一些配置】
                // com.alibaba.dubbo.remoting.transport.AbstractServer.reset
                server.reset(url);
            }
        }
    }

    /**
     * 创建并启动通信服务器
     *
     * @param url
     * @return
     */
    private ExchangeServer createServer(URL url) {
        // 1 默认开启 在 Server 关闭的时候，只能发送 ReadOnly 请求（todo 优雅停机）
        url = url.addParameterIfAbsent(Constants.CHANNEL_READONLYEVENT_SENT_KEY, Boolean.TRUE.toString());

        // 2 默认开启 心跳 【heartbeat参数会在HeaderExchangeServer启动心跳计时器使用】,默认值为 60000，表示默认的心跳时间间隔为 60 秒 （todo 健康检测）
        url = url.addParameterIfAbsent(Constants.HEARTBEAT_KEY, String.valueOf(Constants.DEFAULT_HEARTBEAT));

        // 3 检测SERVER_KEY参数指定的Transporter扩展实现是否合法, 即Dubbo SPI扩展是否存在，默认是Netty
        String str = url.getParameter(Constants.SERVER_KEY, Constants.DEFAULT_REMOTING_SERVER);
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported server type: " + str + ", url: " + url);
        }

        // 4 设置编码解码器参数 ，默认为 DubboCountCodec（todo 设置编解码）
        url = url.addParameter(Constants.CODEC_KEY, DubboCodec.NAME);

        // 5 创建启动服务器
        ExchangeServer server;
        try {
            // 5.1 通过Exchangers门面类，创建ExchangeServer对象。
            // 需要传入 ExchangeHandler 对象，该对象用于处理通道相关事件
            server = Exchangers.bind(url, requestHandler);
        } catch (RemotingException e) {
            throw new RpcException("Fail to start server(url: " + url + ") " + e.getMessage(), e);
        }

        // 6 校验Client 的 Dubbo SPI拓展是否存在。可指定netty,mina
        str = url.getParameter(Constants.CLIENT_KEY);
        if (str != null && str.length() > 0) {
            // 获取所有的Transporter 实现类名称集合，比如 netty,mina
            Set<String> supportedTypes = ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions();
            // 检测当前Dubbo 所支持的Transporter实现类名称列表中是否包含client所表示的 Transporter ，若不包含则抛出异常
            if (!supportedTypes.contains(str)) {
                throw new RpcException("Unsupported client type: " + str);
            }
        }
        return server;
    }

    /**
     * 进行序列化优化，注册需要优化的类
     *
     * @param url
     * @throws RpcException
     */
    private void optimizeSerialization(URL url) throws RpcException {
        // 获得 optimizer 序列化优化器 配置项
        String className = url.getParameter(Constants.OPTIMIZER_KEY, "");

        // 如果系统中没有指定序列化优化器就直接返回
        if (StringUtils.isEmpty(className) || optimizers.contains(className)) {
            return;
        }

        logger.info("Optimizing the serialization process for Kryo, FST, etc...");

        try {
            // 根据 序列化优化器名 加载 SerializationOptimizer 实现类
            Class clazz = Thread.currentThread().getContextClassLoader().loadClass(className);

            // 是否是 SerializationOptimizer.class，或者 是SerializationOptimizer 的子类
            if (!SerializationOptimizer.class.isAssignableFrom(clazz)) {
                throw new RpcException("The serialization optimizer " + className + " isn't an instance of " + SerializationOptimizer.class.getName());
            }

            // 创建 SerializationOptimizer 对象
            SerializationOptimizer optimizer = (SerializationOptimizer) clazz.newInstance();

            // 没有要优化的类直接返回
            if (optimizer.getSerializableClasses() == null) {
                return;
            }

            // 将要优化的类注册到 SerializableClassRegistry 中 （todo 在使用 Kryo,FST 等序列化算法时，会读取该集合中的类，完成注册）
            for (Class c : optimizer.getSerializableClasses()) {
                SerializableClassRegistry.registerClass(c);
            }

            // 将 序列化优化器实现类名 加入到缓存中
            optimizers.add(className);
        } catch (ClassNotFoundException e) {
            throw new RpcException("Cannot find the serialization optimizer class: " + className, e);
        } catch (InstantiationException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);
        } catch (IllegalAccessException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);
        }
    }

    @Override
    public <T> Invoker<T> refer(Class<T> serviceType, URL url) throws RpcException {
        // 初始化序列化优化器
        optimizeSerialization(url);

        // 创建 DubboInvoker 对象
        DubboInvoker<T> invoker = new DubboInvoker<T>(serviceType, url, getClients(url), invokers);

        // 添加到 invokers 中
        invokers.add(invoker);

        return invoker;
    }

    /**
     * 0 Dubbo 协议支持两种模式的网络连接，一种是共享Client 即所有调用公用一个连接。另一个是建立多个连接。
     * 1 创建底层发送请求和接收响应的Client，即创建客户端与服务端的长连接。【如果设置connections配置项，就会有多个client】
     * 2 ExchangeClient实际上并不具有通信能力，它需要更底层的客户端实例进行通信，如：NettyClient,MinaClient等，默认情况下，Dubbo 使用NettyClient进行通信
     * 3 针对 共享连接和独享连接的处理：
     * 3.1 当使用独享连接时，针对每个Service 建立固定数量的连接
     * 3.2 当使用共享连接时，
     *
     * @param url
     * @return 远程通信客户端
     */
    private ExchangeClient[] getClients(URL url) {
        // 是否使用共享连接
        boolean service_share_connect = false;

        // 获取connections 配置项，该值决定了后续建立连接的数量。不配置的情况下默认为0，并使用共享连接的方式，建立一条共享连接。
        int connections = url.getParameter(Constants.CONNECTIONS_KEY, 0);

        // 如果没有连接数的相关配置，默认使用共享连接的方式，且连接数为 1
        // Dubbo 在 2.7 版本中支持共享连接数的配置 SHARE_CONNECTIONS_KEY
        if (connections == 0) {
            service_share_connect = true;
            connections = 1;
        }

        // 创建连接服务提供者的 ExchangeClient 对象数组
        // todo 本质上是因为，针对接口引用服务时，共享连接以服务端 host:ip 缓存到 DubboProtocol 中；而独享连接不会被缓存；
        ExchangeClient[] clients = new ExchangeClient[connections];
        for (int i = 0; i < clients.length; i++) {
            //  1 共享连接
            if (service_share_connect) {
                clients[i] = getSharedClient(url);

                // 2 取独享连接，connections 的值为多少就会创建几个独享连接，在调用时会轮流使用。
                // 注意和 Dubbo 负载均衡的区别。
            } else {
                clients[i] = initClient(url);
            }
        }
        return clients;
    }

    /**
     * 共享连接。以Service地址 （host:port） 做区分，一个地址只会建立对应的一个连接
     */
    private ExchangeClient getSharedClient(URL url) {

        // 1 获取从注册中心拉取的服务提供者的地址（ip:port），连接服务自然需要知道服务地址
        String key = url.getAddress();

        // 2 从 referenceClientMap 中获取与该地址连接的带有引用记数功能的ExchangeClient
        ReferenceCountExchangeClient client = referenceClientMap.get(key);
        if (client != null) {
            if (!client.isClosed()) {
                /** 若未关闭，增加指向该Client 的数量 {@link #refenceCount}  */
                client.incrementAndGetCount();
                return client;
                // 若已关闭，移除
            } else {
                referenceClientMap.remove(key);
            }
        }

        // 新增锁对象
        locks.putIfAbsent(key, new Object());
        // 针对指定地址的客户端创建进行加锁，这里使用分区加锁可以提高并发度
        synchronized (locks.get(key)) {
            // double check
            if (referenceClientMap.containsKey(key)) {
                return referenceClientMap.get(key);
            }

            // 3 初始化 ExchangeClient 客户端
            ExchangeClient exchangeClient = initClient(url);

            // 4 使用装饰者模式将initClient返回的HeaderExchangeClient实例或LazyConnectExchangeClient实例封装为ReferenceCountExchangeClient对象
            // 注意，在使用共享连接时需要注意一个问题，如果两个以上的Invoker 共享这个连接的话，那么必须所有的Invoker 都关闭才能关闭连接。
            client = new ReferenceCountExchangeClient(exchangeClient, ghostClientMap);

            // 5 添加到缓存集合
            referenceClientMap.put(key, client);
            // 6 新建了ExchangeClient，不需要进行兜底，移除兜底集合 ghostClientMap 中的元素
            ghostClientMap.remove(key);

            //将作为锁标识的元素从集合中移除
            locks.remove(key);
            return client;
        }
    }

    /**
     * 建立独享连接。
     */
    private ExchangeClient initClient(URL url) {

        // 1. 获取客户端类型，默认为netty。下面逻辑会检查该扩展
        String str = url.getParameter(Constants.CLIENT_KEY, url.getParameter(Constants.SERVER_KEY, Constants.DEFAULT_REMOTING_CLIENT));

        // 2. 设置编解码器Codec2的扩展名,即DubboCountCodec {@link DubboCountCodec} todo 编解码
        url = url.addParameter(Constants.CODEC_KEY, DubboCodec.NAME);

        // 3. 默认开启heartbeat，todo 健康检测
        url = url.addParameterIfAbsent(Constants.HEARTBEAT_KEY, String.valueOf(Constants.DEFAULT_HEARTBEAT));

        // 校验配置的Client 的 Dubbo SPI拓展是否存在，若不存在，抛出RpcException
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported client type: " + str + "," +
                    " supported client type is " + StringUtils.join(ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions(), " "));
        }

        // 4 连接服务器，创建客户端
        ExchangeClient client;
        try {

            // 4.1 如果配置了延迟创建连接的特性
            if (url.getParameter(Constants.LAZY_CONNECT_KEY, false)) {
                // 创建延迟建立连接的对象（在请求时才会初始化连接）
                client = new LazyConnectExchangeClient(url, requestHandler);

                // 4.2 未使用延迟连接功能，则通过Exchangers的 connect 方法创建 ExchangeClient 客户端，这里是 HeaderExchangeClient
            } else {
                client = Exchangers.connect(url, requestHandler);
            }
        } catch (RemotingException e) {
            throw new RpcException("Fail to create remoting client for service(" + url + "): " + e.getMessage(), e);
        }
        return client;
    }

    /**
     * 销毁所有协议的真正执行逻辑，销毁所有通信 ExchangeClient 和 ExchangeServer
     * 说明：
     * 1 为什么要关闭Server和Client，是因为一个应用程序即可能是服务提供者，又是服务消费者，因此都要关闭
     * 2 这里是先注销了服务提供者，再注销服务消费者，是因为对于一个RPC调用，服务提供者属于上游，先切断上游服务。
     */
    @Override
    public void destroy() {
        // 1 销毁所有通信服务器 ExchangeServer
        for (String key : new ArrayList<String>(serverMap.keySet())) {
            // 1.1 先从缓存中删除通信服务器
            ExchangeServer server = serverMap.remove(key);
            if (server != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close dubbo server: " + server.getLocalAddress());
                    }
                    // 在close()方法中，下层（如HeaderExchangeServer）会发送ReadOnly请求、阻塞指定时间、关闭底层的定时任务、关闭相关线程池，最终，会断开所有连接，关闭Server。
                    // 这些逻辑在前文介绍HeaderExchangeServer、NettyServer等实现的时候
                    // 在优雅停机的等待时长内关闭 [保证了服务平滑的下线]
                    server.close(ConfigUtils.getServerShutdownTimeout());
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }

        // 2 销毁所有通信客户端 ExchangeClient
        for (String key : new ArrayList<String>(referenceClientMap.keySet())) {
            // 2.1 先从缓存中删除通信客户端
            ExchangeClient client = referenceClientMap.remove(key);
            if (client != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close dubbo connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
                    }

                    // ReferenceCountExchangeClient 中只有引用减到 0，底层的 Client 才会真正销毁
                    // 在优雅停机的等待时长内关闭 【保证在处理的请求能够尽可能的在优雅停机时间内完成处理】
                    client.close(ConfigUtils.getServerShutdownTimeout());
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }

        // 3 销毁所有的通信客户端 LazyConnectExchangeClient
        for (String key : new ArrayList<String>(ghostClientMap.keySet())) {
            // 3.1 先从缓存中删除
            ExchangeClient client = ghostClientMap.remove(key);
            if (client != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close dubbo connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
                    }
                    // 在优雅停机的等待时长内关闭
                    client.close(ConfigUtils.getServerShutdownTimeout());
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }

        // 清理 stub 缓存
        stubServiceMethodsMap.clear();

        // 4 执行父类 AbstractProtocol 的销毁方法
        super.destroy();
    }
}
