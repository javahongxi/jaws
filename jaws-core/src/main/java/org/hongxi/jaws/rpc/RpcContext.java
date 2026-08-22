package org.hongxi.jaws.rpc;

import org.hongxi.jaws.common.UrlParam;

import java.util.HashMap;
import java.util.Map;

/**
 * Thread-local context that holds per-request state for an RPC invocation,
 * including request, response, attachments, and the resolved server URL.
 */
public class RpcContext {
    private static final ThreadLocal<RpcContext> LOCAL_CONTEXT = ThreadLocal.withInitial(RpcContext::new);
    private final Map<Object, Object> attributes = new HashMap<>();
    private final Map<String, String> attachments = new HashMap<>();
    private Request request;
    private Response response;
    // The actual service address recorded after the consumer-side invocation
    private URL serverUrl;

    public static RpcContext getContext() {
        return LOCAL_CONTEXT.get();
    }

    public static void destroy() {
        LOCAL_CONTEXT.remove();
    }

    public static RpcContext init(Request request) {
        RpcContext context = new RpcContext();
        if (request != null) {
            context.setRequest(request);
            Map<String, String> reqAttachments = request.getAttachments();
            if (reqAttachments != null && !reqAttachments.isEmpty()) {
                context.attachments.putAll(reqAttachments);
            }
        }
        LOCAL_CONTEXT.set(context);
        return context;
    }

    public String getRequestId() {
        if (request != null) {
            return String.valueOf(request.getRequestId());
        }
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

    public void setRpcAttachment(String key, String value) {
        attachments.put(key, value);
    }

    public String getRpcAttachment(String key) {
        return attachments.get(key);
    }

    public void removeRpcAttachment(String key) {
        attachments.remove(key);
    }

    public Map<String, String> getRpcAttachments() {
        return attachments;
    }

    public Request getRequest() {
        return request;
    }

    public void setRequest(Request request) {
        this.request = request;
    }

    public Response getResponse() {
        return response;
    }

    public void setResponse(Response response) {
        this.response = response;
    }

    public URL getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(URL serverUrl) {
        this.serverUrl = serverUrl;
    }

    /**
     * Returns the caller's IP address (available only on the Provider side).
     *
     * @return the caller IP address, or null if not available
     */
    public String getCallerIp() {
        if (request != null) {
            return request.getAttachments().get(UrlParam.Server.HOST.getName());
        }
        return null;
    }
}