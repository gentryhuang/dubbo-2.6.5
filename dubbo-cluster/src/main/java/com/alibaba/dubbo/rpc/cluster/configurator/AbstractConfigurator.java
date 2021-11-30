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
package com.alibaba.dubbo.rpc.cluster.configurator;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.rpc.cluster.Configurator;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * AbstractOverrideConfigurator，实现公用的配置规则的匹配，排序的逻辑
 */
public abstract class AbstractConfigurator implements Configurator {

    /**
     * 配置规则url
     */
    private final URL configuratorUrl;

    public AbstractConfigurator(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("configurator url == null");
        }
        this.configuratorUrl = url;
    }

    /**
     * 获得配置规则URL
     *
     * @return
     */
    @Override
    public URL getUrl() {
        return configuratorUrl;
    }

    /**
     * 设置配置规则到指定URl中
     *
     * @param url 待应用配置规则的URL
     * @return
     */
    @Override
    public URL configure(URL url) {

        // 1 参数检查，配置规则中必须要有 ip
        if (configuratorUrl == null || configuratorUrl.getHost() == null || url == null || url.getHost() == null) {
            return url;
        }

        //  2 配置规则Url有端口，则说明这个配置规则Url是操作某个服务提供者的，可以通过配置Url的特性参数来控制服务提供者。配置成功后，既可以在服务提供者端生效也可以在服务消费端生效。
        if (configuratorUrl.getPort() != 0) {
            // 配置规则Url有端口，且和待处理的url的端口一致
            if (url.getPort() == configuratorUrl.getPort()) {
                // 因为操作的是服务提供者，所以这里使用的是url的host
                return configureIfMatch(url.getHost(), url);
            }

            // 3 配置规则Url没有端口
        } else {

            //  3.1 如果 url 是消费端地址，目的是控制一个特定的消费者实例，只在消费端生效，服务端收到后忽略
            if (url.getParameter(Constants.SIDE_KEY, Constants.PROVIDER).equals(Constants.CONSUMER)) {
                // NetUtils.getLocalHost() 是消费端注册到注册中心的地址
                return configureIfMatch(NetUtils.getLocalHost(), url);

                // 3.2 如果 url 是服务端地址，意图匹配全部服务提供者。【注意：这种情况暂不支持指定机器服务提供者】
            } else if (url.getParameter(Constants.SIDE_KEY, Constants.CONSUMER).equals(Constants.PROVIDER)) {
                // 对所有服务端生效，因此地址必须是0.0.0.0，否则它将不会执行到此if分支
                return configureIfMatch(Constants.ANYHOST_VALUE, url);
            }
        }
        return url;
    }

    /**
     * 给传入的URL使用配置规则，主要做了两个任务：
     * <p>
     * 1 判断要使用配置规则的URL是否符合规则
     * 2 配置规则Url去除parameters中条件参数后，应用到目标Url上，对目标Url的parameters是直接全覆盖，还是选择性覆盖，根据具体的配置规则对象。
     *
     * @param host
     * @param url
     * @return
     */
    private URL configureIfMatch(String host, URL url) {

        // 1 匹配 host
        // 如果配置 url 的 host 为 0.0.0.0，或者配置 url 的 host 等于传入的 host，则继续匹配应用。否则直接返回url
        if (Constants.ANYHOST_VALUE.equals(configuratorUrl.getHost()) || host.equals(configuratorUrl.getHost())) {

            // 2 获得配置url中的 application，即应用名
            String configApplication = configuratorUrl.getParameter(Constants.APPLICATION_KEY, configuratorUrl.getUsername());
            // 3 获得传入 url 的application，即应用名
            String currentApplication = url.getParameter(Constants.APPLICATION_KEY, url.getUsername());

            //4 匹配应用， 如果配置url的应用名为null，或者为 "*"，或者和url的应用名相同，则执行配置规则逻辑。
            if (configApplication == null || Constants.ANY_VALUE.equals(configApplication) || configApplication.equals(currentApplication)) {

                // 5 排除不能动态修改的属性，除了四个内置的，还可以包括  带有"～"开头的key、"application" 、 "side"
                Set<String> conditionKeys = new HashSet<String>();
                // category
                conditionKeys.add(Constants.CATEGORY_KEY);
                // check
                conditionKeys.add(Constants.CHECK_KEY);
                // dynamic
                conditionKeys.add(Constants.DYNAMIC_KEY);
                // enabled
                conditionKeys.add(Constants.ENABLED_KEY);

                /**
                 * 遍历配置url的 parameter 参数集合
                 * 1 把符合要求的条件加入到 conditionKeys 中，即：带有"～"开头的key、"application" 、 "side"
                 * 2 判断传入的url是否匹配配置规则Url的条件，注意是parameter部分比较，并且不是整个parameter集合的比较，只是  "～"开头的key 或 "application" 或 "side" 这个三个key/valu的比较
                 */
                for (Map.Entry<String, String> entry : configuratorUrl.getParameters().entrySet()) {
                    // 参数key
                    String key = entry.getKey();
                    // 参数key对应的value
                    String value = entry.getValue();

                    // 如果 配置url的parameter参数的key是： "～" 或 "application" 或 "side"，那么也加入到配置Url的条件集合中，需要剔除，不能参与应用到目标URL
                    if (key.startsWith("~") || Constants.APPLICATION_KEY.equals(key) || Constants.SIDE_KEY.equals(key)) {
                        // 把key加入到条件集合中，用于剔除
                        conditionKeys.add(key);

                        // 如果目标URL中不存在配置URL中的剔除参数值（以 ～ 开头的参数），则说明url不匹配配置规则，直接返回url
                        if (value != null && !Constants.ANY_VALUE.equals(value) && !value.equals(url.getParameter(key.startsWith("~") ? key.substring(1) : key))) {
                            return url;
                        }
                    }
                }

                // 从配置Url中排除不能动态修改的属性，然后把剩余的属性配置到URL中
                return doConfigure(url, configuratorUrl.removeParameters(conditionKeys));
            }
        }

        return url;
    }

    /**
     * 排序首先按照ip进行排序，所有ip的优先级都高于0.0.0.0，当ip相同时，会按照priority参数值进行排序
     * <p>
     * Sort by host, priority
     * 1. the url with a specific host ip should have higher priority than 0.0.0.0
     * 2. if two url has the same host, compare by priority value；
     *
     * @param o
     * @return
     */
    @Override
    public int compareTo(Configurator o) {
        if (o == null) {
            return -1;
        }

        // 1 根据 ip 升序
        int ipCompare = getUrl().getHost().compareTo(o.getUrl().getHost());

        // 2 如果 ip 相同，则按照 priority 降序
        if (ipCompare == 0) {
            int i = getUrl().getParameter(Constants.PRIORITY_KEY, 0),
                    j = o.getUrl().getParameter(Constants.PRIORITY_KEY, 0);
            return i < j ? -1 : (i == j ? 0 : 1);
        } else {
            return ipCompare;
        }
    }

    /**
     * 将配置规则配置到url中
     *
     * @param currentUrl 目标url
     * @param configUrl  配置url
     * @return
     */
    protected abstract URL doConfigure(URL currentUrl, URL configUrl);


    public static void main(String[] args) {
        System.out.println(URL.encode("timeout=100"));
    }

}
