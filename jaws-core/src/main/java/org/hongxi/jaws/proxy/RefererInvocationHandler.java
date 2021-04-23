package org.hongxi.jaws.proxy;

import org.hongxi.jaws.cluster.Cluster;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.hongxi.jaws.common.util.ReflectUtils;
import org.hongxi.jaws.common.util.RequestIdGenerator;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Referer;
import org.hongxi.jaws.rpc.ResponseFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Created by shenhongxi on 2021/4/23.
 */
public class RefererInvocationHandler<T> extends AbstractRefererHandler<T> implements InvocationHandler {

    private static final Logger log = LoggerFactory.getLogger(RefererInvocationHandler.class);

    public RefererInvocationHandler(Class<T> clz, List<Cluster<T>> clusters) {
        this.clz = clz;
        this.clusters = clusters;
        init();
        interfaceName = JawsFrameworkUtils.removeAsyncSuffix(clz.getName());
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (isLocalMethod(method)) {
            if ("toString".equals(method.getName())) {
                return clustersToString();
            }
            if ("equals".equals(method.getName())) {
                return proxyEquals(args[0]);
            }
            if ("hashCode".equals(method.getName())) {
                return this.clusters == null ? 0 : this.clusters.hashCode();
            }
            throw new JawsServiceException("can not invoke local method:" + method.getName());
        }

        DefaultRequest request = new DefaultRequest();
        request.setRequestId(RequestIdGenerator.getRequestId());
        request.setArguments(args);
        String methodName = method.getName();
        boolean async = false;
        if (methodName.endsWith(JawsConstants.ASYNC_SUFFIX) && method.getReturnType().equals(ResponseFuture.class)) {
            methodName = JawsFrameworkUtils.removeAsyncSuffix(methodName);
            async = true;
        }
        request.setMethodName(methodName);
        request.setParametersDesc(ReflectUtils.getMethodParamDesc(method));
        request.setInterfaceName(interfaceName);

        return invokeRequest(request, getRealReturnType(async, this.clz, method, methodName), async);
    }

    /**
     * tostring,equals,hashCode,finalize等接口未声明的方法不进行远程调用
     *
     * @param method
     * @return
     */
    public boolean isLocalMethod(Method method) {
        if (method.getDeclaringClass().equals(Object.class)) {
            try {
                Method interfaceMethod = clz.getDeclaredMethod(method.getName(), method.getParameterTypes());
                return false;
            } catch (Exception e) {
                return true;
            }
        }
        return false;
    }

    private String clustersToString() {
        StringBuilder sb = new StringBuilder();
        for (Cluster<T> cluster : clusters) {
            sb.append("{protocol:").append(cluster.getUrl().getProtocol());
            List<Referer<T>> referers = cluster.getReferers();
            if (referers != null) {
                for (Referer<T> refer : referers) {
                    sb.append("[").append(refer.getUrl().toSimpleString()).append(", available:").append(refer.isAvailable()).append("]");
                }
            }
            sb.append("}");
        }
        return sb.toString();
    }

    private boolean proxyEquals(Object o) {
        if (o == null || this.clusters == null) {
            return false;
        }
        if (o instanceof List) {
            return this.clusters == o;
        } else {
            return o.equals(this.clusters);
        }
    }

    private Class<?> getRealReturnType(boolean asyncCall, Class<?> clazz, Method method, String methodName) {
        if (asyncCall) {
            try {
                Method m = clazz.getMethod(methodName, method.getParameterTypes());
                return m.getReturnType();
            } catch (Exception e) {
                log.warn("RefererInvocationHandler get real return type fail.", e);
                return method.getReturnType();
            }
        } else {
            return method.getReturnType();
        }
    }

}