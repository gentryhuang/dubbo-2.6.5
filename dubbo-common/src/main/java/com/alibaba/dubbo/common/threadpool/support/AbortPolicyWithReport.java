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
package com.alibaba.dubbo.common.threadpool.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.JVMUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 拒绝策略实现类，这里打印JStack，分析线程状态
 * <p>
 * Abort Policy.
 * Log warn info when abort.
 */
public class AbortPolicyWithReport extends ThreadPoolExecutor.AbortPolicy {

    protected static final Logger logger = LoggerFactory.getLogger(AbortPolicyWithReport.class);

    /**
     * 线程
     */
    private final String threadName;
    /**
     * URL对象
     */
    private final URL url;

    /**
     * 最后打印时间
     */
    private static volatile long lastPrintTime = 0;

    /**
     * 信号量
     */
    private static Semaphore guard = new Semaphore(1);

    public AbortPolicyWithReport(String threadName, URL url) {
        this.threadName = threadName;
        this.url = url;
    }

    /**
     * 执行拒绝逻辑
     *
     * @param r
     * @param e
     */
    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        // 打印告警日志
        String msg = String.format("Thread pool is EXHAUSTED!" +
                        " Thread Name: %s, Pool Size: %d (active: %d, core: %d, max: %d, largest: %d), Task: %d (completed: %d)," +
                        " Executor status:(isShutdown:%s, isTerminated:%s, isTerminating:%s), in %s://%s:%d!",
                threadName, e.getPoolSize(), e.getActiveCount(), e.getCorePoolSize(), e.getMaximumPoolSize(), e.getLargestPoolSize(),
                e.getTaskCount(), e.getCompletedTaskCount(), e.isShutdown(), e.isTerminated(), e.isTerminating(),
                url.getProtocol(), url.getIp(), url.getPort());
        logger.warn(msg);

        // 打印JStack，分析线程状态
        dumpJStack();

        // 抛出 RejectedExecutionException 异常
        throw new RejectedExecutionException(msg);
    }

    /**
     * 打印JStack，用来分析线程状态
     */
    private void dumpJStack() {
        // 当前时间
        long now = System.currentTimeMillis();

        //10分钟打印一次
        if (now - lastPrintTime < 10 * 60 * 1000) {
            return;
        }

        // 获得信号量 ,用以保证同一时间有且仅有一个线程执行打印
        if (!guard.tryAcquire()) {
            return;
        }

        // 创建线程池，后台执行打印逻辑
        Executors.newSingleThreadExecutor().execute(new Runnable() {
            @Override
            public void run() {

                // 获得系统
                String dumpPath = url.getParameter(Constants.DUMP_DIRECTORY, System.getProperty("user.home"));

                SimpleDateFormat sdf;

                // 获得路径
                String OS = System.getProperty("os.name").toLowerCase();

                // window system don't support ":" in file name
                if (OS.contains("win")) {
                    sdf = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
                } else {
                    sdf = new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss");
                }

                String dateStr = sdf.format(new Date());

                // 输出流
                FileOutputStream jstackStream = null;
                try {
                    jstackStream = new FileOutputStream(new File(dumpPath, "Dubbo_JStack.log" + "." + dateStr));

                    // 打印JStack 信息
                    JVMUtil.jstack(jstackStream);
                } catch (Throwable t) {
                    logger.error("dump jstack error", t);
                } finally {
                    // 释放信号量
                    guard.release();
                    if (jstackStream != null) {
                        try {
                            jstackStream.flush();
                            jstackStream.close();
                        } catch (IOException e) {
                        }
                    }
                }

                // 记录最后打印时间
                lastPrintTime = System.currentTimeMillis();
            }
        });

    }

}
