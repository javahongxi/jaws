package org.hongxi.summer.rpc;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by shenhongxi on 2020/6/26.
 */
public class RpcContext {
    private static final ThreadLocal<RpcContext> LOCAL_CONTEXT = ThreadLocal.withInitial(() -> new RpcContext());
    private Map<Object, Object> attributes = new HashMap<>();
    private Map<String, String> attachments = new HashMap<>();
    private Request request;
    private Response response;
    private String clientRequestId;

    public static RpcContext getContext() {
        return LOCAL_CONTEXT.get();
    }

    public String getRequestId() {
        if (clientRequestId != null) return clientRequestId;
        if (request != null) return String.valueOf(request.getRequestId());
        return null;
    }

    public void putAttribute(Object key, Object value) {
        attributes.put(key, value);
    }

    public Object getAttribute(Object key) {
        return attributes.get(key);
    }

    public void removeAttribute(Object key) {
        attributes.remove(key);
    }

    public Map<Object, Object> getAttributes() {
        return attributes;
    }
}
