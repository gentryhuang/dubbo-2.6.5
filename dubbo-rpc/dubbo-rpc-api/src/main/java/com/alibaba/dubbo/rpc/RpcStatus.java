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
package com.alibaba.dubbo.rpc;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * URL statistics. (API, Cached, ThreadSafe)  RPC状态。计数器。
 * 可以计入如下纬度统计：
 * 1 基于服务URL
 * 2 基于服务URL + 方法
 *
 * @see com.alibaba.dubbo.rpc.filter.ActiveLimitFilter
 * @see com.alibaba.dubbo.rpc.filter.ExecuteLimitFilter
 * @see com.alibaba.dubbo.rpc.cluster.loadbalance.LeastActiveLoadBalance
 */
public class RpcStatus {
    /**
     * 服务状态信息
     * key: URL
     * value: RpcStatus 计数器
     */
    private static final ConcurrentMap<String, RpcStatus> SERVICE_STATISTICS = new ConcurrentHashMap<String, RpcStatus>();
    /**
     * 服务每个方法的状态信息
     * key1: URL
     * key2: 方法名
     * RpcStatus 计数器
     */
    private static final ConcurrentMap<String, ConcurrentMap<String, RpcStatus>> METHOD_STATISTICS = new ConcurrentHashMap<String, ConcurrentMap<String, RpcStatus>>();

    private final ConcurrentMap<String, Object> values = new ConcurrentHashMap<String, Object>();
    /**
     * 当前并发度
     *
     * @see com.alibaba.dubbo.rpc.filter.ActiveLimitFilter
     */
    private final AtomicInteger active = new AtomicInteger();
    /**
     * 总调用次数
     */
    private final AtomicLong total = new AtomicLong();
    /**
     * 总调用失败次数
     */
    private final AtomicInteger failed = new AtomicInteger();
    /**
     * 总调用时长，单位： 毫秒
     */
    private final AtomicLong totalElapsed = new AtomicLong();
    /**
     * 总调用失败时长，单位：毫秒
     */
    private final AtomicLong failedElapsed = new AtomicLong();
    /**
     * 所有调用中最长的耗时，单位：毫秒
     */
    private final AtomicLong maxElapsed = new AtomicLong();
    /**
     * 所有失败调用中最长的耗时，单位：毫秒
     */
    private final AtomicLong failedMaxElapsed = new AtomicLong();
    /**
     * 所有成功调用中最长的耗时，单位：毫秒
     */
    private final AtomicLong succeededMaxElapsed = new AtomicLong();
    /**
     * Semaphore used to control concurrency limit set by `executes`
     * <p>
     * 服务执行信号量【包含服务执行信号量大小】
     *
     * @see com.alibaba.dubbo.rpc.filter.ExecuteLimitFilter
     */
    private volatile Semaphore executesLimit;
    /**
     * 服务执行信号量大小
     */
    private volatile int executesPermits;

    private RpcStatus() {
    }

    /**
     * 根据服务URL为纬度的获得RpcStatus
     *
     * @param url
     * @return status
     */
    public static RpcStatus getStatus(URL url) {
        // URL的字符串
        String uri = url.toIdentityString();
        // 是否存在
        RpcStatus status = SERVICE_STATISTICS.get(uri);

        // 不存在则创建，并且放入缓存中
        if (status == null) {
            SERVICE_STATISTICS.putIfAbsent(uri, new RpcStatus());
            status = SERVICE_STATISTICS.get(uri);
        }
        return status;
    }

    /**
     * 移除基于服务URL对应的RpcStatus
     *
     * @param url
     */
    public static void removeStatus(URL url) {
        String uri = url.toIdentityString();
        SERVICE_STATISTICS.remove(uri);
    }

    /**
     * 根据 服务URL + 方法 获得RpcStatus
     *
     * @param url
     * @param methodName
     * @return status
     */
    public static RpcStatus getStatus(URL url, String methodName) {
        String uri = url.toIdentityString();
        // 获得方法集合
        ConcurrentMap<String, RpcStatus> map = METHOD_STATISTICS.get(uri);
        if (map == null) {
            METHOD_STATISTICS.putIfAbsent(uri, new ConcurrentHashMap<String, RpcStatus>());
            map = METHOD_STATISTICS.get(uri);
        }
        RpcStatus status = map.get(methodName);
        if (status == null) {
            map.putIfAbsent(methodName, new RpcStatus());
            status = map.get(methodName);
        }
        return status;
    }

    /**
     * @param url
     */
    public static void removeStatus(URL url, String methodName) {
        String uri = url.toIdentityString();
        ConcurrentMap<String, RpcStatus> map = METHOD_STATISTICS.get(uri);
        if (map != null) {
            map.remove(methodName);
        }
    }

    /**
     * 服务调用开始计数
     *
     * @param url        URL 对象
     * @param methodName 方法名
     */
    public static void beginCount(URL url, String methodName) {
        // SERVICE_STATISTICS -> 基于服务URL的计数
        beginCount(getStatus(url));
        // METHOD_STATISTICS -> 基于服务URL + 方法的计数
        beginCount(getStatus(url, methodName));
    }

    /**
     * 计数 - 调用中的次数
     *
     * @param status
     */
    private static void beginCount(RpcStatus status) {
        status.active.incrementAndGet();
    }

    /**
     * 服务调用结束的计数
     *
     * @param url        URL对象
     * @param methodName 方法名
     * @param elapsed    时长，毫秒
     * @param succeeded  是否成功
     */
    public static void endCount(URL url, String methodName, long elapsed, boolean succeeded) {
        // SERVICE_STATISTICS -> 基于服务URL的计数
        endCount(getStatus(url), elapsed, succeeded);
        // METHOD_STATISTICS -> 基于服务URL + 方法的计数
        endCount(getStatus(url, methodName), elapsed, succeeded);
    }

    /**
     * 结束计数
     *
     * @param status
     * @param elapsed
     * @param succeeded
     */
    private static void endCount(RpcStatus status, long elapsed, boolean succeeded) {
        // 调用的次数要递减
        status.active.decrementAndGet();

        // 总调用次数递增
        status.total.incrementAndGet();

        // 总调用时长递增
        status.totalElapsed.addAndGet(elapsed);

        // 更新最大调用时长
        if (status.maxElapsed.get() < elapsed) {
            status.maxElapsed.set(elapsed);
        }

        // 是否调用成功
        if (succeeded) {
            // 更新最大成功调用时长
            if (status.succeededMaxElapsed.get() < elapsed) {
                status.succeededMaxElapsed.set(elapsed);
            }
        } else {
            // 调用失败次数递增
            status.failed.incrementAndGet();
            // 总调用失败时长
            status.failedElapsed.addAndGet(elapsed);

            // 更新最大失败调用时长
            if (status.failedMaxElapsed.get() < elapsed) {
                status.failedMaxElapsed.set(elapsed);
            }
        }
    }

    /**
     * set value.
     *
     * @param key
     * @param value
     */
    public void set(String key, Object value) {
        values.put(key, value);
    }

    /**
     * get value.
     *
     * @param key
     * @return value
     */
    public Object get(String key) {
        return values.get(key);
    }

    /**
     * get active.
     *
     * @return active
     */
    public int getActive() {
        return active.get();
    }

    /**
     * get total.
     *
     * @return total
     */
    public long getTotal() {
        return total.longValue();
    }

    /**
     * get total elapsed.
     *
     * @return total elapsed
     */
    public long getTotalElapsed() {
        return totalElapsed.get();
    }

    /**
     * get average elapsed.
     *
     * @return average elapsed
     */
    public long getAverageElapsed() {
        long total = getTotal();
        if (total == 0) {
            return 0;
        }
        return getTotalElapsed() / total;
    }

    /**
     * get max elapsed.
     *
     * @return max elapsed
     */
    public long getMaxElapsed() {
        return maxElapsed.get();
    }

    /**
     * get failed.
     *
     * @return failed
     */
    public int getFailed() {
        return failed.get();
    }

    /**
     * get failed elapsed.
     *
     * @return failed elapsed
     */
    public long getFailedElapsed() {
        return failedElapsed.get();
    }

    /**
     * get failed average elapsed.
     *
     * @return failed average elapsed
     */
    public long getFailedAverageElapsed() {
        long failed = getFailed();
        if (failed == 0) {
            return 0;
        }
        return getFailedElapsed() / failed;
    }

    /**
     * get failed max elapsed.
     *
     * @return failed max elapsed
     */
    public long getFailedMaxElapsed() {
        return failedMaxElapsed.get();
    }

    /**
     * get succeeded.
     *
     * @return succeeded
     */
    public long getSucceeded() {
        return getTotal() - getFailed();
    }

    /**
     * get succeeded elapsed.
     *
     * @return succeeded elapsed
     */
    public long getSucceededElapsed() {
        return getTotalElapsed() - getFailedElapsed();
    }

    /**
     * get succeeded average elapsed.
     *
     * @return succeeded average elapsed
     */
    public long getSucceededAverageElapsed() {
        long succeeded = getSucceeded();
        if (succeeded == 0) {
            return 0;
        }
        return getSucceededElapsed() / succeeded;
    }

    /**
     * get succeeded max elapsed.
     *
     * @return succeeded max elapsed.
     */
    public long getSucceededMaxElapsed() {
        return succeededMaxElapsed.get();
    }

    /**
     * Calculate average TPS (Transaction per second).
     *
     * @return tps
     */
    public long getAverageTps() {
        if (getTotalElapsed() >= 1000L) {
            return getTotal() / (getTotalElapsed() / 1000L);
        }
        return getTotal();
    }

    /**
     * 获取信号量
     * <p>
     * Get the semaphore for thread number. Semaphore's permits is decided by {@link Constants#EXECUTES_KEY}
     *
     * @param maxThreadNum value of {@link Constants#EXECUTES_KEY}
     * @return thread number semaphore
     */
    public Semaphore getSemaphore(int maxThreadNum) {
        if (maxThreadNum <= 0) {
            return null;
        }

        // 若信号量不存在，或者信号量大小改变，则创建新的信号量
        if (executesLimit == null || executesPermits != maxThreadNum) {
            synchronized (this) {
                if (executesLimit == null || executesPermits != maxThreadNum) {
                    // 创建信号量
                    executesLimit = new Semaphore(maxThreadNum);
                    executesPermits = maxThreadNum;
                }
            }
        }

        // 返回信号量
        return executesLimit;
    }
}