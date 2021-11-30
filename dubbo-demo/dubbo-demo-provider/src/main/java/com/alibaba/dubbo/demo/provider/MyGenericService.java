package com.alibaba.dubbo.demo.provider;

import com.alibaba.dubbo.rpc.service.GenericException;
import com.alibaba.dubbo.rpc.service.GenericService;
import org.springframework.stereotype.Service;

/**
 * MyGenericService
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2021/05/01
 * <p>
 * desc：
 */
@Service
public class MyGenericService implements GenericService {
    @Override
    public Object $invoke(String method, String[] parameterTypes, Object[] args) throws GenericException {
        if ("sayHello".equals(method)) {
            return "print " + args[0];
        }
        return "not find method!";
    }
}
