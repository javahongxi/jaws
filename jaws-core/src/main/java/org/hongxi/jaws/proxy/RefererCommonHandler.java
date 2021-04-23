package org.hongxi.jaws.proxy;

import org.hongxi.jaws.cluster.Cluster;
import org.hongxi.jaws.common.util.RequestIdGenerator;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Request;

import java.util.List;
import java.util.Map;

/**
 * Created by shenhongxi on 2021/4/23.
 */
public class RefererCommonHandler<T> extends AbstractRefererHandler<T> implements CommonHandler {

    public RefererCommonHandler(String interfaceName, List<Cluster<T>> clusters) {
        this.interfaceName = interfaceName;
        this.clusters = clusters;
        init();
    }

    public Object call(String methodName, Object[] arguments, Class returnType, Map<String, String> attachments, boolean async) throws Throwable {
        DefaultRequest request = new DefaultRequest();
        request.setRequestId(RequestIdGenerator.getRequestId());
        request.setInterfaceName(interfaceName);
        request.setMethodName(methodName);
        request.setArguments(arguments);
        request.setAttachments(attachments);
        return invokeRequest(request, returnType, async);
    }

    @Override
    public Object call(String methodName, Object[] arguments, Class returnType) throws Throwable {
        return call(methodName, arguments, returnType, null, false);
    }

    @Override
    public Object asyncCall(String methodName, Object[] arguments, Class returnType) throws Throwable {
        return call(methodName, arguments, returnType, null, true);
    }

    @Override
    public Object call(Request request, Class returnType) throws Throwable {
        return invokeRequest(request, returnType, false);
    }

    @Override
    public Object asyncCall(Request request, Class returnType) throws Throwable {
        return invokeRequest(request, returnType, true);
    }

    @Override
    public Request buildRequest(String methodName, Object[] arguments) {
        return buildRequest(interfaceName, methodName, arguments);
    }

    @Override
    public Request buildRequest(String interfaceName, String methodName, Object[] arguments) {
        DefaultRequest request = new DefaultRequest();
        request.setRequestId(RequestIdGenerator.getRequestId());
        request.setInterfaceName(interfaceName);
        request.setMethodName(methodName);
        request.setArguments(arguments);
        return request;
    }

}