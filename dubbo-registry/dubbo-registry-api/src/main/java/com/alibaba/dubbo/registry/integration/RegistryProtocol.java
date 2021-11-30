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
package com.alibaba.dubbo.registry.integration;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.Registry;
import com.alibaba.dubbo.registry.RegistryFactory;
import com.alibaba.dubbo.registry.RegistryService;
import com.alibaba.dubbo.registry.support.FailbackRegistry;
import com.alibaba.dubbo.registry.support.ProviderConsumerRegTable;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.cluster.Configurator;
import com.alibaba.dubbo.rpc.protocol.InvokerWrapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.alibaba.dubbo.common.Constants.ACCEPT_FOREIGN_IP;
import static com.alibaba.dubbo.common.Constants.QOS_ENABLE;
import static com.alibaba.dubbo.common.Constants.QOS_PORT;
import static com.alibaba.dubbo.common.Constants.VALIDATION_KEY;

/**
 * registry -> RegistryProtocol
 */
public class RegistryProtocol implements Protocol {

    private final static Logger logger = LoggerFactory.getLogger(RegistryProtocol.class);
    /**
     * 单例，在dubbo SPI中，被初始化，有且仅有一次。
     */
    private static RegistryProtocol INSTANCE;

    /**
     * 订阅URL与监听器的映射关系
     * key: 服务提供方订阅 URL
     * value: 监听器
     */
    private final Map<URL, NotifyListener> overrideListeners = new ConcurrentHashMap<URL, NotifyListener>();

    /**
     * 服务暴露映射关系
     * key: 服务提供者的 URL 字符串形式
     * value: 服务暴露器 Export
     */
    private final Map<String, ExporterChangeableWrapper<?>> bounds = new ConcurrentHashMap<String, ExporterChangeableWrapper<?>>();

    /**
     * Cluster 自适应拓展实现类对象
     */
    private Cluster cluster;

    /**
     * Protocol 自适应拓展实现类，通过Dubbo SPI自动注入 【Dubbo IOC ，Setter注入 】
     */
    private Protocol protocol;
    /**
     * RegistryFactory 自适应拓展实现类，通过Dubbo SPI自动注入
     */
    private RegistryFactory registryFactory;

    /**
     * 代理工厂
     */
    private ProxyFactory proxyFactory;

    public RegistryProtocol() {
        INSTANCE = this;
    }

    public static RegistryProtocol getRegistryProtocol() {
        if (INSTANCE == null) {
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(Constants.REGISTRY_PROTOCOL); // load
        }
        return INSTANCE;
    }

    //Filter the parameters that do not need to be output in url(Starting with .)
    private static String[] getFilteredKeys(URL url) {
        Map<String, String> params = url.getParameters();
        if (params != null && !params.isEmpty()) {
            List<String> filteredKeys = new ArrayList<String>();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (entry != null && entry.getKey() != null && entry.getKey().startsWith(Constants.HIDE_KEY_PREFIX)) {
                    filteredKeys.add(entry.getKey());
                }
            }
            return filteredKeys.toArray(new String[filteredKeys.size()]);
        } else {
            return new String[]{};
        }
    }

    public void setCluster(Cluster cluster) {
        this.cluster = cluster;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setRegistryFactory(RegistryFactory registryFactory) {
        this.registryFactory = registryFactory;
    }

    public void setProxyFactory(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }

    @Override
    public int getDefaultPort() {
        return 9090;
    }

    public Map<URL, NotifyListener> getOverrideListeners() {
        return overrideListeners;
    }

    /**
     * 包含两步操作：
     * 1 获取注册中心
     * 2 向注册中心注册服务
     *
     * @param registryUrl
     * @param registedProviderUrl
     */
    public void register(URL registryUrl, URL registedProviderUrl) {
        // 获取Registry
        Registry registry = registryFactory.getRegistry(registryUrl);

        // 注册服务，本质上是将服务配置数据写入到注册中心上。如Zookeeper注册中心，以某个路径节点形式写入到Zookeeper上。这个方法定义在 FailbackRegistry 抽象类中
        registry.register(registedProviderUrl);
    }

    /**
     * 该方法包含：
     * 1 服务导出
     * 2 服务注册
     * 3 数据订阅
     * <p>
     * 说明：
     * RegistryProtocol通过向注册中心注册OverrideListener监听器，从而集成配置规则到 服务提供者 中
     *
     * @param originInvoker 封装服务的 AbstractProxyInvoker 对象
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> Exporter<T> export(final Invoker<T> originInvoker) throws RpcException {


        // 1 暴露服务并启动 Server 服务
        // 此处Local指的是，本地启动服务(不同协议会启动不动的服务Server，如：NettyServer，tomcat等)，打开端口，但是不包括向注册中心注册服务。
        final ExporterChangeableWrapper<T> exporter = doLocalExport(originInvoker);

        // 2 将 "registry://"协议转换成注册中心协议，以zk为例：zookeeper://127.0.0.1/com.xxx...XxxService?kev=value&kev=value...
        URL registryUrl = getRegistryUrl(originInvoker);

        // 3 根据 registryUrl 获得（创建）注册中心对象，如ZookeeperRegistry
        final Registry registry = getRegistry(originInvoker);

        // 4 获得真正要注册到注册中心的URL,其中会删除一些多余的参数信息
        final URL registeredProviderUrl = getRegisteredProviderUrl(originInvoker);

        // 服务提供者URL参数项 register , 服务提供者是否注册到配置中心。默认是true
        boolean register = registeredProviderUrl.getParameter("register", true);

        // 5 向本地注册表中记录服务提供者信息（包含服务对应的注册中心地址），该信息用于Dubbo QOS
        ProviderConsumerRegTable.registerProvider(originInvoker, registryUrl, registeredProviderUrl);

        /**
         * 6 根据 register 的值决定是否注册服务
         * 注意：
         *   服务注册对于Dubbo 来说不是必需的，通过服务直连的方式就可以绕过注册中心。但是一般不这样做，直连方式不利于服务治理，仅推荐在测试环境测试服务时使用。
         */
        if (register) {

            // 将服务提供者地址写入到注册中心【如：使用zk会先创建服务提供者的节点路径】
            register(registryUrl, registeredProviderUrl);

            // 标记向本地注册表已经注册了服务提供者
            ProviderConsumerRegTable.getProviderWrapper(originInvoker).setReg(true);
        }

        /** 7 使用OverrideListener 对象，服务暴露时会订阅配置规则 configurators [为了在服务配置发生变化时，重新导出服务。具体的使用场景应该当我们通过 Dubbo 管理后台修改了服务配置后，Dubbo 得到服务配置被修改的通知，然后重新导出服务] */

        // 7.1 基于 registeredProviderUrl 构建服务提供方订阅URL，如 provider://...?...&category=configurators&check=false
        final URL overrideSubscribeUrl = getSubscribedOverrideUrl(registeredProviderUrl);

        // 7.2 创建 OverrideListener 监听器，用于监听订阅的URL映射的目录有没有发生变化
        // 即 获取要监听的配置目录，这里会在ProviderURL的基础上添加category=configurators参数，并封装成对 OverrideListener 记录到 overrideListeners 集合中
        final OverrideListener overrideSubscribeListener = new OverrideListener(overrideSubscribeUrl, originInvoker);

        // 7.3 将订阅放入缓存
        overrideListeners.put(overrideSubscribeUrl, overrideSubscribeListener);

        // 7.4 向注册中心进行订阅，主要是监听该服务的configurators节点
        registry.subscribe(overrideSubscribeUrl, overrideSubscribeListener);

        // 8 创建并返回DestroyableExporter
        return new DestroyableExporter<T>(exporter, originInvoker, overrideSubscribeUrl, registeredProviderUrl);
    }

    /**
     * 暴露服务，并启动 Server
     *
     * @param originInvoker 封装服务的 AbstractProxyInvoker 对象
     * @param <T>
     * @return
     */
    @SuppressWarnings("unchecked")
    private <T> ExporterChangeableWrapper<T> doLocalExport(final Invoker<T> originInvoker) {

        // 1 获得在 bounds 缓存中的key
        // 其实是服务提供者暴露地址, 即从Invoker的URL中Map属性集合中获取key为'export'的服务提供者暴露地址然后去除不需要的信息，该地址要写到注册中心上。
        String key = getCacheKey(originInvoker);

        // 2 从 bounds 缓存中获得，是否存在已经暴露过的服务
        ExporterChangeableWrapper<T> exporter = (ExporterChangeableWrapper<T>) bounds.get(key);

        // 3 不存在则会进行服务暴露，并启动 Server
        if (exporter == null) {
            synchronized (bounds) {
                exporter = (ExporterChangeableWrapper<T>) bounds.get(key);

                // 未暴露过，进行暴露服务
                if (exporter == null) {
                    /**
                     * 1 创建InvokerDelegete 对象
                     * 2 InvokerDelegete 继承了 InvokerWrapper类，增加了getInvoker方法，获取非InvokerDelegete的Invoker对象，
                     *  通过getInvoker方法可以看出来，可能会存在InvokerDelete.invoker也是InvokerDelegete类型的情况
                     * 3 todo InvokerDelegete 中的 URL 是服务提供者的URL，在使用 protocol.export 时，使用具体协议暴露服务
                     */
                    final Invoker<?> invokerDelegete = new InvokerDelegete<T>(originInvoker, getProviderUrl(originInvoker));
                    /**
                     * 🌟使用服务提供者的协议将 InvokerDelegete 转换成 Exporter
                     * 1 使用Protocol协议暴露服务并创建ExporterChangeableWrapper 对象 （构造参数： Exporter,Invoker,这样Invoker和Exporter就形成了绑定关系）
                     * 2 具体调用哪个协议的export方法，看Dubbo SPI选择哪个，就调用对应协议的XXXProtocol#export(Invoker)
                     *   - todo 注意这里使用 SPI 找具体的 Protocol 实现时的过程，该方法是一个标注 @Adaptive 方法，会根据 InvokerDelegete 获取 url ，而该 url 是 originInvoker 中的 Provider URL ，如对应的协议为 Dubbo
                     * 3 底层启动服务
                     */
                    exporter = new ExporterChangeableWrapper<T>((Exporter<T>) protocol.export(invokerDelegete), originInvoker);

                    // 添加到 bounds 缓存
                    bounds.put(key, exporter);
                }
            }
        }

        // 4 返回 Export
        return exporter;
    }

    /**
     * 对修改了URL的Invoker重新暴露
     *
     * @param originInvoker
     * @param newInvokerUrl
     */
    @SuppressWarnings("unchecked")
    private <T> void doChangeLocalExport(final Invoker<T> originInvoker, URL newInvokerUrl) {
        // 获得originInvoker 的 URL串
        String key = getCacheKey(originInvoker);
        // 检验对应的Exporter是否存在，不存在打印告警日志
        final ExporterChangeableWrapper<T> exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
        if (exporter == null) {
            logger.warn(new IllegalStateException("error state, exporter should not be null"));
        } else {
            // 创建 InvokerDelegete 对象
            final Invoker<T> invokerDelegete = new InvokerDelegete<T>(originInvoker, newInvokerUrl);
            // 重新暴露Invoker，并设置到缓存中
            exporter.setExporter(protocol.export(invokerDelegete));
        }
    }

    /**
     * Get an instance of registry based on the address of invoker
     *
     * @param originInvoker
     * @return
     */
    private Registry getRegistry(final Invoker<?> originInvoker) {
        URL registryUrl = getRegistryUrl(originInvoker);
        return registryFactory.getRegistry(registryUrl);
    }

    /**
     * 获得注册中心的URL，这是加载注册中心URL的反向流程
     *
     * @param originInvoker
     * @return
     */
    private URL getRegistryUrl(Invoker<?> originInvoker) {
        // 拿到注册中心的URL
        URL registryUrl = originInvoker.getUrl();
        // 如果URL的协议是 registry ，那么就从参数中尝试取出registry的值，这个值就是注册中心真正的协议，然后将它设置为注册中心的协议，同时移除参数里面registry参数
        if (Constants.REGISTRY_PROTOCOL.equals(registryUrl.getProtocol())) {
            // 从URL中取不到registry,就使用默认值dubbo
            String protocol = registryUrl.getParameter(Constants.REGISTRY_KEY, Constants.DEFAULT_DIRECTORY);
            // 设置真正的协议，并移除参数中的registry参数
            registryUrl = registryUrl.setProtocol(protocol).removeParameter(Constants.REGISTRY_KEY);
        }
        return registryUrl;
    }


    /**
     * Return the url that is registered to the registry and filter the url parameter once
     *
     * @param originInvoker
     * @return
     */
    private URL getRegisteredProviderUrl(final Invoker<?> originInvoker) {
        // 从注册中心的URL中获取 export 参数的值，即服务提供这URL
        URL providerUrl = getProviderUrl(originInvoker);
        // 移除多余的参数，因为这些参数注册到注册中心没有实际的用途，这样可以减轻ZK的压力
        return providerUrl.removeParameters(getFilteredKeys(providerUrl)) // 移除 .开头的的参数
                .removeParameter(Constants.MONITOR_KEY) // monitor
                .removeParameter(Constants.BIND_IP_KEY) // bind.ip
                .removeParameter(Constants.BIND_PORT_KEY) // bind.port
                .removeParameter(QOS_ENABLE) // qos.enable
                .removeParameter(QOS_PORT) // qos.port
                .removeParameter(ACCEPT_FOREIGN_IP) // qos.accept.foreign.ip
                .removeParameter(VALIDATION_KEY); // validation
    }


    /**
     * 1 将协议改为provider
     * 2 添加参数： category=configurators 和 check=false
     *
     * @param registedProviderUrl
     * @return
     */
    private URL getSubscribedOverrideUrl(URL registedProviderUrl) {
        return registedProviderUrl
                // 设置协议为 'provider'
                .setProtocol(Constants.PROVIDER_PROTOCOL)
                // 追加 parameters 参数： category:configurators,check:false
                .addParameters(Constants.CATEGORY_KEY, Constants.CONFIGURATORS_CATEGORY, Constants.CHECK_KEY, String.valueOf(false));
    }

    /**
     * Get the address of the providerUrl through the url of the invoker
     *
     * @param origininvoker
     * @return
     */
    private URL getProviderUrl(final Invoker<?> origininvoker) {
        String export = origininvoker.getUrl().getParameterAndDecoded(Constants.EXPORT_KEY);
        if (export == null || export.length() == 0) {
            throw new IllegalArgumentException("The registry export url is null! registry: " + origininvoker.getUrl());
        }

        URL providerUrl = URL.valueOf(export);
        return providerUrl;
    }

    /**
     * Get the key cached in bounds by invoker
     *
     * @param originInvoker
     * @return
     */
    private String getCacheKey(final Invoker<?> originInvoker) {
        URL providerUrl = getProviderUrl(originInvoker);
        String key = providerUrl.removeParameters("dynamic", "enabled").toFullString();
        return key;
    }

    /**
     * 多个Invoker也会被封装成一个
     *
     * @param type Service class
     * @param url  URL address for the remote service
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {

        // 1 获得真实的注册中心的URL
        url = url.setProtocol(url.getParameter(Constants.REGISTRY_KEY, Constants.DEFAULT_REGISTRY)).removeParameter(Constants.REGISTRY_KEY);

        // 2 根据注册中心地址获得注册中心
        Registry registry = registryFactory.getRegistry(url);

        // todo 这是干嘛的？为什么要给RegistryService 类型生成Invoker
        if (RegistryService.class.equals(type)) {
            return proxyFactory.getInvoker((T) registry, type, url);
        }

        // 3 获得服务引用配置参数集合Map，这个是从 refer 参数获取的
        Map<String, String> qs = StringUtils.parseQueryString(url.getParameterAndDecoded(Constants.REFER_KEY));

        // 4 从消费配置参数中获取group属性
        String group = qs.get(Constants.GROUP_KEY);
        // todo 分组聚合 group="a,b" or group="*"
        if (group != null && group.length() > 0) {

            // 如果多个分组 -> todo 分组聚合，将每个组的服务调用一次，然后聚合结果
            if ((Constants.COMMA_SPLIT_PATTERN.split(group)).length > 1 || "*".equals(group)) {

                // 通过SPI加载 MergeableCluster实例，并调用 doRefer 继续执行引用服务逻辑。
                // todo com.alibaba.dubbo.registry.integration.RegistryDirectory.toMergeMethodInvokerMap 该方法已经使用自适应 cluster 合并了分组 Invoker
                return doRefer(getMergeableCluster(), registry, type, url);
            }
        }


        /**
         * 执行服务引用
         * todo 服务分组的使用，如果消费方有指定 group 属性，那么该属性发挥的作用在服务订阅时，对 Provider 的过滤。注意和分组聚合的区别。
         *
         * @see com.alibaba.dubbo.common.utils.UrlUtils#isMatch(com.alibaba.dubbo.common.URL, com.alibaba.dubbo.common.URL)
         */
        return doRefer(cluster, registry, type, url);
    }

    private Cluster getMergeableCluster() {
        return ExtensionLoader.getExtensionLoader(Cluster.class).getExtension("mergeable");
    }

    /**
     * 执行服务引用，返回Invoker对象
     *
     * @param cluster  Cluster 对象
     * @param registry 注册中心对象
     * @param type     服务接口类型
     * @param url      注册中心URL
     * @param <T>      泛型
     * @return Invoker 对象
     */
    private <T> Invoker<T> doRefer(Cluster cluster, Registry registry, Class<T> type, URL url) {
        /**
         * 1 创建RegistryDirectory对象【服务目录】，并设置注册中心到它的属性，该对象包含了注册中心的所有服务提供者 List<Invoker>
         * 2 其中在其父类AbstractDirectory中会创建List<Router>routers
         */
        RegistryDirectory<T> directory = new RegistryDirectory<T>(type, url);
        // 设置注册中心和协议
        directory.setRegistry(registry);
        directory.setProtocol(protocol);


        // 获得服务引用配置集合 parameters。注意：url传入RegistryDirectory后，经过处理并重新创建，所以 url != directory.url，
        Map<String, String> parameters = new HashMap<String, String>(directory.getUrl().getParameters());

        // 生成消费者URL，协议为consumer，具体的参数是 RegistryURL 中 refer 参数指定的参数。在 RegistryDirectory 创建时进行初始化。
        URL subscribeUrl = new URL(Constants.CONSUMER_PROTOCOL, parameters.remove(Constants.REGISTER_IP_KEY), 0, type.getName(), parameters);

        // 向注册中心注册服务消费者，在consumers目录下
        if (!Constants.ANY_VALUE.equals(url.getServiceInterface())
                && url.getParameter(Constants.REGISTER_KEY, true)) {
            // 在 subscribeUrl中添加category=consumers和check=false参数
            registry.register(subscribeUrl.addParameters(Constants.CATEGORY_KEY, Constants.CONSUMERS_CATEGORY,
                    Constants.CHECK_KEY, String.valueOf(false)));
        }

        /** 向注册中心订阅 服务提供者 + 路由规则 + 配置规则 节点下的数据，完成订阅后，RegistryDirectory 会收到这几个子节点信息
         * 注意：
         * 1 第一次发起订阅时会进行一次数据拉取，同时触发RegistryDirectory#notify方法，这里的通知数据是某一个类目的全量数据，如：providers,router，configurators 类目数据。
         *   并且当通知providers数据时，在RegistryDirectory#toInvokers方法内完成Invoker转换
         * 2 当注册中心宕机，订阅会失败进入catch 逻辑 --->  {@link FailbackRegistry#subscribe(com.alibaba.dubbo.common.URL, com.alibaba.dubbo.registry.NotifyListener)}
         *
         */
        directory.subscribe(subscribeUrl.addParameter(Constants.CATEGORY_KEY,
                Constants.PROVIDERS_CATEGORY
                        + "," + Constants.CONFIGURATORS_CATEGORY
                        + "," + Constants.ROUTERS_CATEGORY));

        /** 创建Invoker对象 ，可能有多个服务提供者，因此需要将多个服务提供者合并为一个
         *
         * 由于一个服务可能部署在多台服务器上，这样就会在 providers 产生多个节点，这个时候就需要 Cluster 将多个服务节点合并为一个，并生成一个 Invoker，这一个Invoker代表了多个。
         * Cluster默认为FailoverCluster实例，支持服务调用重试
         */
        Invoker invoker = cluster.join(directory);

        // 向本地注册表，注册消费者
        ProviderConsumerRegTable.registerConsumer(invoker, url, subscribeUrl, directory);
        return invoker;
    }

    /**
     * Invoker 销毁时注销端口和map【bounds】中服务实例等资源
     * 说明：
     * 此方法用来销毁 ExporterChangeableWrapper 在 bounds 的映射。
     */
    @Override
    public void destroy() {

        // 获得 ExporterChangeableWrapper 数组
        List<Exporter<?>> exporters = new ArrayList<Exporter<?>>(bounds.values());

        // 取消所有 Exporter 的暴露。
        for (Exporter<?> exporter : exporters) {
            exporter.unexport();
        }

        // 清空 Invoker与Exporter绑定关闭的缓存
        bounds.clear();
    }

    public static class InvokerDelegete<T> extends InvokerWrapper<T> {
        private final Invoker<T> invoker;

        /**
         * @param invoker
         * @param url     invoker.getUrl return this value
         */
        public InvokerDelegete(Invoker<T> invoker, URL url) {
            super(invoker, url);
            this.invoker = invoker;
        }

        public Invoker<T> getInvoker() {
            if (invoker instanceof InvokerDelegete) {
                return ((InvokerDelegete<T>) invoker).getInvoker();
            } else {
                return invoker;
            }
        }
    }

    /**
     * 重新export
     * 1 protocol 中的exporter destroy 问题
     * 2 要求registryProtocol返回的exporter 可以正常destroy
     * 3 notify后不需要重新向注册中心注册
     * 4 export 方法传入的invoker最好能一直作为exporter的invoker
     */
    private class OverrideListener implements NotifyListener {

        /**
         * 订阅URL
         */
        private final URL subscribeUrl;
        /**
         * 原始 Invoker 对象
         */
        private final Invoker originInvoker;

        public OverrideListener(URL subscribeUrl, Invoker originalInvoker) {
            this.subscribeUrl = subscribeUrl;
            this.originInvoker = originalInvoker;
        }

        /**
         * 对原本注册的subscribeUrl进行校验，如果url发生了变化，那么要重新export
         *
         * @param urls 已注册信息列表，总不能为空【没有匹配的就是创建一个empty：//...】，含义同 {@link com.alibaba.dubbo.registry.RegistryService#lookup(URL)}的返回值
         */
        @Override
        public synchronized void notify(List<URL> urls) {
            logger.debug("original override urls: " + urls);

            // 获取匹配的规则配置URL列表
            List<URL> matchedUrls = getMatchedUrls(urls, subscribeUrl);

            logger.debug("subscribe url: " + subscribeUrl + ", override urls: " + matchedUrls);
            // 没有匹配的
            if (matchedUrls.isEmpty()) {
                return;
            }

            // 将配置规则URL集合，转换成对应的配置规则Configurator集合
            List<Configurator> configurators = RegistryDirectory.toConfigurators(matchedUrls);

            // 最原始的invoker
            final Invoker<?> invoker;
            if (originInvoker instanceof InvokerDelegete) {
                invoker = ((InvokerDelegete<?>) originInvoker).getInvoker();
            } else {
                invoker = originInvoker;
            }

            // 获取服务提供者的URL信息 ， 从 export 参数获取
            URL originUrl = RegistryProtocol.this.getProviderUrl(invoker);

            // 在doLocalExport方法中已经存放在这里，获取服务提供者URL串，从export 参数获取
            String key = getCacheKey(originInvoker);

            // 判断服务是否暴露过
            ExporterChangeableWrapper<?> exporter = bounds.get(key);

            if (exporter == null) {
                logger.warn(new IllegalStateException("error state, exporter should not be null"));
                return;
            }

            //获得Invoker当前的URL对象，可能已经被之前的配置规则合并过。
            URL currentUrl = exporter.getInvoker().getUrl();

            //基于originUrl，合并配置规则，生成新的 newUrl 对象 。 todo 配置规则生效的地方
            URL newUrl = getConfigedInvokerUrl(configurators, originUrl);

            // 配置规则生效，需要重新暴露服务
            if (!currentUrl.equals(newUrl)) {
                // 重新将invoker 暴露为exporter
                RegistryProtocol.this.doChangeLocalExport(originInvoker, newUrl);
                logger.info("exported provider url changed, origin url: " + originUrl + ", old export url: " + currentUrl + ", new export url: " + newUrl);
            }
        }

        /**
         * 获得匹配的规则配置URL集合
         *
         * @param configuratorUrls
         * @param currentSubscribe
         * @return
         */
        private List<URL> getMatchedUrls(List<URL> configuratorUrls, URL currentSubscribe) {
            List<URL> result = new ArrayList<URL>();
            for (URL url : configuratorUrls) {
                URL overrideUrl = url;
                // 兼容旧版本
                if (url.getParameter(Constants.CATEGORY_KEY) == null && Constants.OVERRIDE_PROTOCOL.equals(url.getProtocol())) {
                    overrideUrl = url.addParameter(Constants.CATEGORY_KEY, Constants.CONFIGURATORS_CATEGORY);
                }

                // 根据关键属性匹配
                if (UrlUtils.isMatch(currentSubscribe, overrideUrl)) {
                    result.add(url);
                }
            }
            return result;
        }

        /**
         * 合并配置的url
         *
         * @param configurators
         * @param url
         * @return
         */
        private URL getConfigedInvokerUrl(List<Configurator> configurators, URL url) {
            for (Configurator configurator : configurators) {
                url = configurator.configure(url);
            }
            return url;
        }
    }

    /**
     * exporter proxy, establish the corresponding relationship between the returned exporter and the exporter exported by the protocol, and can modify the relationship at the time of override.
     * <p>
     * <p>
     * Exporter可变的包装器，建立Invoker和Exporter的绑定关系。
     * 说明：
     * 保存了原有的Invoker对象，因为服务提供者可能会发生变化，比如服务提供者集成了配置规则Configurator
     *
     * @param <T>
     */
    private class ExporterChangeableWrapper<T> implements Exporter<T> {

        /**
         * 原Invoker 对象
         */
        private final Invoker<T> originInvoker;
        /**
         * 暴露的Exporter 对象
         */
        private Exporter<T> exporter;

        public ExporterChangeableWrapper(Exporter<T> exporter, Invoker<T> originInvoker) {
            this.exporter = exporter;
            this.originInvoker = originInvoker;
        }

        public Invoker<T> getOriginInvoker() {
            return originInvoker;
        }

        @Override
        public Invoker<T> getInvoker() {
            return exporter.getInvoker();
        }

        public void setExporter(Exporter<T> exporter) {
            this.exporter = exporter;
        }

        /**
         * 取消服务暴露
         */
        @Override
        public void unexport() {
            String key = getCacheKey(this.originInvoker);
            // Invoker销毁时注销map中服务实例等资源
            bounds.remove(key);
            // 取消暴露
            exporter.unexport();
        }
    }

    /**
     * 可销毁的Exporter
     *
     * @param <T>
     */
    static private class DestroyableExporter<T> implements Exporter<T> {

        public static final ExecutorService executor = Executors.newSingleThreadExecutor(new NamedThreadFactory("Exporter-Unexport", true));
        /**
         * 暴露的Exporter 对象
         */
        private Exporter<T> exporter;
        /**
         * 原Invoker 对象
         */
        private Invoker<T> originInvoker;
        private URL subscribeUrl;
        private URL registerUrl;

        public DestroyableExporter(Exporter<T> exporter, Invoker<T> originInvoker, URL subscribeUrl, URL registerUrl) {
            this.exporter = exporter;
            this.originInvoker = originInvoker;
            this.subscribeUrl = subscribeUrl;
            this.registerUrl = registerUrl;
        }

        @Override
        public Invoker<T> getInvoker() {
            return exporter.getInvoker();
        }

        @Override
        public void unexport() {
            Registry registry = RegistryProtocol.INSTANCE.getRegistry(originInvoker);
            try {
                //移除已经注册的元数据
                registry.unregister(registerUrl);
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
            try {
                // 去掉订阅配置的监听器
                NotifyListener listener = RegistryProtocol.INSTANCE.overrideListeners.remove(subscribeUrl);
                registry.unsubscribe(subscribeUrl, listener);
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }

            executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        int timeout = ConfigUtils.getServerShutdownTimeout();
                        if (timeout > 0) {
                            logger.info("Waiting " + timeout + "ms for registry to notify all consumers before unexport. Usually, this is called when you use dubbo API");
                            Thread.sleep(timeout);
                        }
                        exporter.unexport();
                    } catch (Throwable t) {
                        logger.warn(t.getMessage(), t);
                    }
                }
            });
        }
    }
}
