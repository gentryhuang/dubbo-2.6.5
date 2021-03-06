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
package com.alibaba.dubbo.rpc.protocol.rmi;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.protocol.AbstractProxyProtocol;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.remoting.RemoteAccessException;
import org.springframework.remoting.rmi.RmiProxyFactoryBean;
import org.springframework.remoting.rmi.RmiServiceExporter;
import org.springframework.remoting.support.RemoteInvocation;
import org.springframework.remoting.support.RemoteInvocationFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.rmi.RemoteException;

/**
 * RmiProtocol ，实现AbstractProxyProtocol 抽象类，rmi:// 协议实现类
 * 说明：
 * 整体实现和dubbo-rpc-http差不多
 */
public class RmiProtocol extends AbstractProxyProtocol {

    /**
     * 默认端口
     */
    public static final int DEFAULT_PORT = 1099;

    public RmiProtocol() {
        super(RemoteAccessException.class, RemoteException.class);
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    @Override
    protected <T> Runnable doExport(final T impl, Class<T> type, URL url) throws RpcException {
        // 创建 RmiServiceExporter 对象
        final RmiServiceExporter rmiServiceExporter = new RmiServiceExporter();
        rmiServiceExporter.setRegistryPort(url.getPort());
        rmiServiceExporter.setServiceName(url.getPath());
        rmiServiceExporter.setServiceInterface(type);
        rmiServiceExporter.setService(impl);
        try {
            rmiServiceExporter.afterPropertiesSet();
        } catch (RemoteException e) {
            throw new RpcException(e.getMessage(), e);
        }

        // 返回取消暴露的回调的 Runnable
        return new Runnable() {
            @Override
            public void run() {
                try {
                    rmiServiceExporter.destroy();
                } catch (Throwable e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T> T doRefer(final Class<T> serviceType, final URL url) throws RpcException {
        // 创建 RmiProxyFactoryBean 对象
        final RmiProxyFactoryBean rmiProxyFactoryBean = new RmiProxyFactoryBean();
        // RMI needs extra parameter since it uses customized remote invocation object

        // 如果远程服务是Dubbo RMI 时，RMI 传输时使用 自定义的远程执行对象，从而传递额外的参数
        if (url.getParameter(Constants.DUBBO_VERSION_KEY, Version.getProtocolVersion()).equals(Version.getProtocolVersion())) {
            // Check dubbo version on provider, this feature only support
            rmiProxyFactoryBean.setRemoteInvocationFactory(new RemoteInvocationFactory() {
                @Override
                public RemoteInvocation createRemoteInvocation(MethodInvocation methodInvocation) {
                    return new RmiRemoteInvocation(methodInvocation);
                }
            });
        }

        // 设置相关参数 【注意，Dubbo 配置中的超时时间对RMI无效，需要使用java 启动参数设置 -Dxxx=yyy】
        rmiProxyFactoryBean.setServiceUrl(url.toIdentityString());
        rmiProxyFactoryBean.setServiceInterface(serviceType);
        rmiProxyFactoryBean.setCacheStub(true);
        rmiProxyFactoryBean.setLookupStubOnStartup(true);
        rmiProxyFactoryBean.setRefreshStubOnConnectFailure(true);
        rmiProxyFactoryBean.afterPropertiesSet();

        // 创建 Service Proxy 对象
        return (T) rmiProxyFactoryBean.getObject();
    }

    @Override
    protected int getErrorCode(Throwable e) {
        if (e instanceof RemoteAccessException) {
            e = e.getCause();
        }
        if (e != null && e.getCause() != null) {
            Class<?> cls = e.getCause().getClass();
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

}
