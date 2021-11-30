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
package com.alibaba.dubbo.rpc.cluster.router.condition;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Router;
import com.alibaba.dubbo.rpc.cluster.directory.AbstractDirectory;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ConditionRouter，基于条件表达式的Router实现类,路由规则在发起一次RPC调用前起到过滤目标服务器地址的作用，过滤后的地址列表，将作为消费端最终发起RPC调用的备选地址。
 * 流程说明：
 * 1 对用户配置的路由规则进行解析，得到匹配项集合,即其对应的匹配值集合和不匹配值集合，
 * 2 当需要进行匹配的时候，根据已经解析好的规则对 消费者URL或服务提供者URL 进行匹配，以达到过滤Invoker的目的，即消费者快要符合哪些规则，提供者要符合哪些规则
 */
public class ConditionRouter implements Router, Comparable<Router> {

    private static final Logger logger = LoggerFactory.getLogger(ConditionRouter.class);

    /**
     * 分组正则匹配，用于切分路由规则的正则表达式
     * 说明：
     * 第一个匹配组-分割符： 用于匹配 "&", "!=", "=" 和 "," 等符号，作为匹配规则的分隔符。允许匹配不到，使用了 * 通配符
     * 第二个匹配组-内容： 这里用于匹配 英文字母，数字等字符，【其实是匹配不是 &!=,】作为匹配规则的匹配内容。 可能出现，这里匹配到了，但是第一匹配组没有匹配到。
     */
    private static Pattern ROUTE_PATTERN = Pattern.compile("([&!=,]*)\\s*([^&!=,\\s]+)");
    /**
     * 路由规则 URL，如： URL.valueOf("route://0.0.0.0/com.foo.BarService?category=routers&dynamic=false&rule=" + URL.encode("host = 10.20.153.10 => host = 10.20.153.11"))
     */
    private final URL url;
    /**
     * 路由规则优先级，用于排序，优先级越大越靠前。优先级越大越靠前执行。默认为0
     */
    private final int priority;
    /**
     * 当路由结果为空时是否强制执行，如果不强制执行，路由匹配结果为空的路由规则将自动失效。如果强制执行，则直接返回空的路由结果。默认为false
     */
    private final boolean force;
    /**
     * 消费者匹配的条件集合，通过解析条件表达式规则 '=>' 之前的部分
     * key: 匹配项
     * value: 匹配项对应的匹配对 【包含匹配项对应的 匹配值集合/不匹配值集合 】
     * 效果：所有参数和消费者的 URL 进行对比，当消费者满足匹配条件时，对该消费者执行后面的过滤规则。
     */
    private final Map<String, MatchPair> whenCondition;
    /**
     * 提供者匹配的条件集合，通过解析条件表达式规则 '=>' 之后的部分
     * key: 匹配项
     * value: 匹配项对应的匹配对 【包含匹配项对应的 匹配值集合/不匹配值集合 】
     * 效果：所有参数和提供者的 URL 进行对比，消费者最终只拿到过滤后的地址列表。
     */
    private final Map<String, MatchPair> thenCondition;

    /**
     * 将条件路由规则解析成预定格式
     *
     * @param url 条件规则 URL
     */
    public ConditionRouter(URL url) {
        this.url = url;

        // 1 获取 priority 和 force 配置
        this.priority = url.getParameter(Constants.PRIORITY_KEY, 0);
        this.force = url.getParameter(Constants.FORCE_KEY, false);
        try {

            // 2 获取路由规则URL中路由规则 rule 参数的值
            String rule = url.getParameterAndDecoded(Constants.RULE_KEY);
            if (rule == null || rule.trim().length() == 0) {
                throw new IllegalArgumentException("Illegal route rule!");
            }

            // 3 剔除掉路由规则中的consumer.或者provider. ，如 consumer.host != 192.168.0.1 & method = * => provider.host != 10.75.25.66
            // 剔除调前缀才是真正的规则
            rule = rule.replace("consumer.", "").replace("provider.", "");

            // 4 根据 "=>" 拆分路由规则
            int i = rule.indexOf("=>");

            // 5 分别获取消费者匹配规则的串 和 服务提供者过滤规则的串
            String whenRule = i < 0 ? null : rule.substring(0, i).trim();
            String thenRule = i < 0 ? rule.trim() : rule.substring(i + 2).trim();

            // 6 将路由规则串解析为key-value形式 ,key为路由规则匹配项，value为匹配对（包含了匹配项对应的 匹配值集合和不匹配值集合）
            // 6.1 解析消费方匹配规则
            Map<String, MatchPair> when = StringUtils.isBlank(whenRule) || "true".equals(whenRule) ? new HashMap<String, MatchPair>() : parseRule(whenRule);

            // 6.2 解析提供者过滤规则
            Map<String, MatchPair> then = StringUtils.isBlank(thenRule) || "false".equals(thenRule) ? null : parseRule(thenRule);

            // 6.3 赋值 消费方匹配条件集合、提供者过滤条件集合
            this.whenCondition = when;
            this.thenCondition = then;

        } catch (ParseException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * 解析配置的路由规则
     *
     * @param rule
     * @return
     * @throws ParseException
     */
    private static Map<String, MatchPair> parseRule(String rule) throws ParseException {

        // 1 定义条件映射集合，key：匹配项  value: 匹配对MatchPair 。
        Map<String, MatchPair> condition = new HashMap<String, MatchPair>();
        if (StringUtils.isBlank(rule)) {
            return condition;
        }

        // 2 存储匹配对，即匹配和不匹配条件
        MatchPair pair = null;

        // 3 匹配对中的 匹配值集合/不匹配值集合的临时变量
        Set<String> values = null;

        // 4 按照 分组正则匹配 配整路由个条件表达式
        final Matcher matcher = ROUTE_PATTERN.matcher(rule);

        /**
         * 5 通过 ROUTE_PATTERN 正则匹配 rule ，遍历匹配结果，
         * 说明：
         * 1 find()方法是部分匹配，是查找输入串中与模式匹配的子串，如果该匹配的串有组还可以使用group()函数。当且仅当输入序列的子序列，匹配规则才会返回true，可能可以匹配多个子串
         * 2 matcher.group() 返回匹配到的子字符串
         * 例子：host = 2.2.2.2 & host != 1.1.1.1 & method = hello
         * 匹配结果：
         * 第一个子序列： host              分组一：""  分组二：host
         * 第二个子序列：= 2.2.2.2          分组一：=   分组二：2.2.2.2
         * 第三个子序列：& host             分组一：&   分组二：host
         * ...
         */
        while (matcher.find()) {

            // 5.1 获取匹配组一的匹配结果，即分隔符
            String separator = matcher.group(1);
            // 5.2 获取匹配组二的匹配结果，即匹配规则项
            String content = matcher.group(2);


            // 6 匹配组一的匹配结果为空，则说明 content 为参数名称
            if (separator == null || separator.length() == 0) {
                // 创建 MatchPair 对象
                pair = new MatchPair();
                // 存储 <匹配项, MatchPair> 键值对，比如 <host, MatchPair>
                condition.put(content, pair);
            }

            // 7 如果匹配组一的匹配结果是 '&'，说明是多个表达式
            else if ("&".equals(separator)) {
                // 先尝试从 condition 中获取content对应的MatchPair，不存在则新建并放入condition中
                if (condition.get(content) == null) {
                    pair = new MatchPair();
                    condition.put(content, pair);
                } else {
                    pair = condition.get(content);
                }
            }

            // 8 如果分隔符为 '='，表示KV的分界线，值是匹配值
            else if ("=".equals(separator)) {
                if (pair == null) {
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                }

                // 匹配对中的匹配值集合，先取再放
                values = pair.matches;

                // 将 content 存入到 MatchPair 的 matches 集合中
                values.add(content);
            }

            // 9 如果分隔符为 '!='，表示KV的分界线，值就是不匹配值
            else if ("!=".equals(separator)) {
                if (pair == null) {
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                }

                // 匹配对中的不匹配值集合，先取再放
                values = pair.mismatches;

                // 将 content 存入到 MatchPair 的 mismatches 集合中
                values.add(content);
            }


            // 10 分隔符为 , 表示某个匹配项有多个值，它们以 ','分隔
            else if (",".equals(separator)) {
                if (values == null || values.isEmpty()) {
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                }

                // 将 content 存入到上一步获取到的 values 中，可能是 matches，也可能是 mismatches
                values.add(content);

                // 11 暂不支持的分割符
            } else {
                throw new ParseException("Illegal route rule \"" + rule
                        + "\", The error char '" + separator + "' at index "
                        + matcher.start() + " before \"" + content + "\".", matcher.start());
            }
        }

        return condition;
    }

    /**
     * 路由，过滤Invoker
     * 说明：
     * 下面的逻辑有的是服务提供者过滤规则，有的是消费者匹配规则，但是该方法传入的url参数目前都是消费者URL: {@link AbstractDirectory#list(com.alibaba.dubbo.rpc.Invocation)} 和 {@link com.alibaba.dubbo.registry.integration.RegistryDirectory#route(java.util.List, java.lang.String)}
     *
     * @param invokers   Invoker 集合
     * @param url        调用者传入，目前都是消费者URL
     * @param invocation 调用信息
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> List<Invoker<T>> route(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException {
        // 判空
        if (invokers == null || invokers.isEmpty()) {
            return invokers;
        }

        try {

            /**
             * 1 优先执行消费者匹配条件，如果匹配失败，说明当前消费者URL不符合消费者匹配规则，直接返回invoker集合即可，无需继续后面的逻辑。
             * 说明：
             * 消费者 ip：192.168.25.100
             * 路由规则：host = 10.20.125.10 => host = 10.2.12.10  : ip为10.20.125.10的消费者调用ip为10.2.12.10的服务提供者
             * 结果：当前消费者ip为192.168.25.100，这条路由规则不适用于当前的消费者，直接返回
             *
             */
            if (!matchWhen(url, invocation)) {
                return invokers;
            }

            // 路由过滤后的Invoker结果集
            List<Invoker<T>> result = new ArrayList<Invoker<T>>();

            // 2 提供者过滤条件未配置的话直接返回空集合，表示无提供者可用
            if (thenCondition == null) {
                logger.warn("The current consumer in the service blacklist. consumer: " + NetUtils.getLocalHost() + ", service: " + url.getServiceKey());
                return result;
            }

            // 3 遍历Invoker集合，逐个判断 Invoker 是否符合提供者过滤条件
            for (Invoker<T> invoker : invokers) {
                if (matchThen(invoker.getUrl(), url)) {
                    result.add(invoker);
                }
            }

            // 4 如果 result 非空，则直接返回过滤后的Invoker 集合
            if (!result.isEmpty()) {
                return result;

                // 5 如果过滤后的Invoker集合为空，根据 force 决定返回空集合还是返回全部 Invoker
                // 如果 force = true，表示强制返回空列表，否则路由结果为空的路由规则将失效
            } else if (force) {
                logger.warn("The route result is empty and force execute. consumer: " + NetUtils.getLocalHost() + ", service: " + url.getServiceKey() + ", router: " + url.getParameterAndDecoded(Constants.RULE_KEY));
                return result;
            }
        } catch (Throwable t) {
            logger.error("Failed to execute condition router rule: " + getUrl() + ", invokers: " + invokers + ", cause: " + t.getMessage(), t);
        }

        // 6 走到这里，说明过滤后的Invoker集合为空，并且非强制执行，则原样返回invoker 集合，即表示该条路由规则失效，忽律路由规则
        return invokers;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    /**
     * 比较，按照 priority 降序，按照 url 升序
     *
     * @param o
     * @return
     */
    @Override
    public int compareTo(Router o) {
        if (o == null || o.getClass() != ConditionRouter.class) {
            return 1;
        }
        ConditionRouter c = (ConditionRouter) o;
        return this.priority == c.priority ? url.toFullString().compareTo(c.url.toFullString()) : (this.priority > c.priority ? 1 : -1);
    }

    /**
     * 对服务消费者进行匹配，如果匹配失败，直接返回Invoker 列表。如果匹配成功，再对服务提供者进行匹配。
     *
     * @param url        消费者 URL
     * @param invocation 调用信息
     * @return
     */
    boolean matchWhen(URL url, Invocation invocation) {
        /**
         * 服务消费者匹配条件为 null 或 空，表示对所有消费方生效，返回true，如：
         *    => host != 10.2.12.10  ，表示所有的消费者都不能调用 IP 为 10.2.12.10
         */
        return whenCondition == null || whenCondition.isEmpty() ||
                matchCondition(whenCondition, url, null, invocation);
    }

    /**
     * 对服务提供者进行匹配
     *
     * @param url   提供者URL合并后的 URL
     * @param param 消费者URL
     * @return
     */
    private boolean matchThen(URL url, URL param) {
        // 服务提供者条件为 null 或 空，表示禁用服务
        return !(thenCondition == null || thenCondition.isEmpty()) &&
                matchCondition(thenCondition, url, param, null);
    }

    /**
     * 匹配条件
     *
     * @param condition  消费者匹配的条件集合/提供者匹配的条件集合
     * @param url        消费者URL/提供者URL合并后的URL
     * @param param      消费者URL，在url参数为提供者URL合并后的URL时才有值。该值仅用于匹配规则引用消费者URL的参数的场景。
     * @param invocation 调用信息
     * @return
     */
    private boolean matchCondition(Map<String, MatchPair> condition, URL url, URL param, Invocation invocation) {

        // 1 将服务提供者或消费者 url 转成 Map
        Map<String, String> sample = url.toMap();
        // 标记是否匹配
        boolean result = false;

        // 2 遍历匹配条件集合
        for (Map.Entry<String, MatchPair> matchPair : condition.entrySet()) {

            //--------------------------- 获取匹配项，确定匹配值 --------------------------/

            // 2.1 获得匹配项名称，如 host,method
            String key = matchPair.getKey();
            // 2.2 匹配项的值
            String sampleValue;

            // 2.3 如果 Invocation 不为null，且匹配项名称为 method 或 methods，表示进行方法匹配
            if (invocation != null && (Constants.METHOD_KEY.equals(key) || Constants.METHODS_KEY.equals(key))) {
                // 从Invocation中获取调用方法名
                sampleValue = invocation.getMethodName();

            } else {

                // 2.4 从服务提供者或者消费者 URL 中获取匹配项的值，如 host、application
                sampleValue = sample.get(key);

                // 2.5 匹配项对应的值不存在，则尝试获取 default.key 的值
                if (sampleValue == null) {
                    sampleValue = sample.get(Constants.DEFAULT_KEY_PREFIX + key);
                }
            }

            //--------------------------- 条件匹配 ------------------------------------------/

            //  3 匹配项的值不为空
            if (sampleValue != null) {
                // 调用匹配项关联的 MatchPair 的 isMatch 方法进行匹配，只要有一个匹配规则匹配失败，就失败
                if (!matchPair.getValue().isMatch(sampleValue, param)) {
                    return false;
                } else {
                    result = true;
                }

                // 4 匹配项的值为空，说明服务提供者或消费者URL中不包含该配置项的值
            } else {

                // 匹配项中的匹配条件 `matches` 不为空，表示匹配失败，返回false
                // 如我们设置了这样一条规则：ip = 10.2.12.10 ，假设 URL 中不包含 ip 参数，此时 ip 匹配项的值为 null，
                // 但路由规则限制了 ip = 10.2.12.10，URL中却没有该配置项，这是不符合规则的，因此返回 false
                if (!matchPair.getValue().matches.isEmpty()) {
                    return false;
                } else {
                    result = true;
                }
            }

        }
        return result;
    }

    /**
     * 匹配对，进行过滤时会按照以下四条规则执行：
     * 1. 当 mismatches 集合为空时，会遍历 matches 集合中的匹配条件，匹配上任意一条即可返回 true。
     * 2. 当 matches 集合为空时，会遍历 mismatches 集合中的匹配条件，匹配上任意一条即返回 false。
     * 3. 当 matches 集合和 mismatches 集合同时不为空时，会优先匹配 mismatches 集合中的条件，匹配上任意一条规则即返回 false。
     * 若 mismatches 中的条件全部匹配失败，才会开始匹配 matches 集合，匹配上任意一条即返回 true。
     * 4. 当以上 3 种情况都失败时，则返回 false
     */
    private static final class MatchPair {
        /**
         * 匹配值集合，待匹配项存在于集合，则说明匹配成功
         */
        final Set<String> matches = new HashSet<String>();
        /**
         * 不匹配值集合，待匹配项存在于集合，则说明匹配失败
         */
        final Set<String> mismatches = new HashSet<String>();

        /**
         * 判断 value 是否匹配 matches + mismatches
         *
         * @param value
         * @param param
         * @return
         */
        private boolean isMatch(String value, URL param) {

            // 1 只匹配 matches，没有匹配上则说明失败了，返回false
            if (!matches.isEmpty() && mismatches.isEmpty()) {
                for (String match : matches) {
                    // 只要入参被 matches 集合中的任意一个元素匹配到，就匹配成功，返回true
                    if (UrlUtils.isMatchGlobPattern(match, value, param)) {
                        return true;
                    }
                }

                // 如果所有匹配值都无法匹配到 value，则匹配失败,返回false
                return false;
            }

            // 2 只匹配 mismatches，没有匹配上，则说明成功了，返回true
            if (!mismatches.isEmpty() && matches.isEmpty()) {
                for (String mismatch : mismatches) {
                    // 只要入参被 mismatches 集合中的任意一个元素匹配到，就匹配失败，返回false
                    if (UrlUtils.isMatchGlobPattern(mismatch, value, param)) {
                        return false;
                    }
                }

                // mismatches 集合中所有元素都无法匹配到入参，则匹配成功，返回 true
                return true;
            }

            // 3 匹配 mismatches + matches，优先去匹配 mismatches
            if (!matches.isEmpty() && !mismatches.isEmpty()) {

                // 只要 mismatches 集合中任意一个元素与入参匹配成功，则匹配失败，就立即返回 false
                for (String mismatch : mismatches) {
                    if (UrlUtils.isMatchGlobPattern(mismatch, value, param)) {
                        return false;
                    }
                }

                // 只要 matches 集合中任意一个元素与入参匹配成功，则匹配成功，就立即返回 true
                for (String match : matches) {
                    if (UrlUtils.isMatchGlobPattern(match, value, param)) {
                        return true;
                    }
                }
                return false;
            }

            // 4 matches 和 mismatches 均为空，此时返回 false
            return false;
        }
    }

    public static void main(String[] args) throws ParseException {
        String str = "host = 2.2.2.2 & host != 1.1.1.1 & method = hello";

        //   Map<String, MatchPair> stringMatchPairMap = parseRule(str);

        final Matcher matcher = ROUTE_PATTERN.matcher(str);


        int i = matcher.groupCount();

        while (matcher.find()) {

            String group = matcher.group();
            System.out.println("group:" + group);


            String group1 = matcher.group(1);
            String group2 = matcher.group(2);

            System.out.println("group1:" + group1 + "  --  " + "group2:" + group2);
        }


    }
}
