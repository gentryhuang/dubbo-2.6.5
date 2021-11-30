package com.alibaba.dubbo.rpc;

/**
 * Client
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2021/11/24
 * <p>
 * desc：
 */
public class Client {
    public static void main(String[] args) {
        Class<RpcInvocation> rpcInvocationClass = RpcInvocation.class;
        String simpleName = rpcInvocationClass.getSimpleName();
        System.out.println(simpleName);

        String toLowerCase = simpleName.toLowerCase();
        System.out.println(toLowerCase);


    }
}
