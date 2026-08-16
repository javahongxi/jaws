package org.hongxi.jaws.proxy;

import org.hongxi.jaws.cluster.Cluster;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.util.ExceptionUtils;
import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Created by shenhongxi on 2021/4/23.
 */
public class AbstractReferenceHandler<T> {

    private static final Logger log = LoggerFactory.getLogger(AbstractReferenceHandler.class);

    protected List<Cluster<T>> clusters;
    protected String interfaceName;

    AbstractReferenceHandler(List<Cluster<T>> clusters, String interfaceName) {
        this.clusters = clusters;
        this.interfaceName = interfaceName;
    }

    Object invoke(Request request, Class<?> returnType) throws Throwable {
        RpcContext context = RpcContext.getContext();

        Map<String, String> attachments = context.getRpcAttachments();
        if (!attachments.isEmpty()) {
            for (Map.Entry<String, String> entry : attachments.entrySet()) {
                request.setAttachment(entry.getKey(), entry.getValue());
            }
        }

        for (Cluster<T> cluster : clusters) {
            request.setAttachment(URLParamType.version.getName(), cluster.getUrl().getVersion());
            request.setAttachment(URLParamType.application.getName(), cluster.getUrl().getApplication());
            request.setAttachment(URLParamType.module.getName(), cluster.getUrl().getModule());

            try {
                return cluster.call(request).getValue();
            } catch (RuntimeException e) {
                if (ExceptionUtils.isBizException(e)) {
                    Throwable t = e.getCause();
                    if (t instanceof Exception) {
                        throw t;
                    }
                    String msg;
                    if (t == null) {
                        msg = "biz exception cause is null, original error: " + e.getMessage();
                    } else {
                        msg = "biz exception cause is a non-Exception Throwable: "
                                + t.getClass().getName() + ", message: " + t.getMessage();
                    }
                    throw new JawsServiceException(msg);
                } else if (!cluster.getUrl().getBoolParameter(URLParamType.throwException)) {
                    log.warn("invoke failed, returning default value as throwException=false: uri={} {}",
                            cluster.getUrl().getUri(), JawsFrameworkUtils.toString(request), e);
                    if (returnType != null && returnType.isPrimitive()) {
                        return PrimitiveDefault.getDefaultReturnValue(returnType);
                    }
                    return null;
                } else {
                    log.error("invoke Error: uri={} {}",
                            cluster.getUrl().getUri(), JawsFrameworkUtils.toString(request), e);
                    throw e;
                }
            }
        }
        throw new JawsServiceException("Reference call Error: cluster not exists, interface=" + interfaceName + " "
                + JawsFrameworkUtils.toString(request), JawsErrorMsgConstants.SERVICE_NOT_FOUND, false);
    }

    /**
     * Invoke with CompletableFuture return type.
     * The returned CompletableFuture completes when the RPC response arrives.
     */
    CompletableFuture<Object> invokeAsync(Request request, Class<?> returnType) {
        RpcContext context = RpcContext.getContext();
        context.putAttribute(JawsConstants.ASYNC_FLAG, true);

        Map<String, String> attachments = context.getRpcAttachments();
        if (!attachments.isEmpty()) {
            for (Map.Entry<String, String> entry : attachments.entrySet()) {
                request.setAttachment(entry.getKey(), entry.getValue());
            }
        }

        CompletableFuture<Object> resultFuture = new CompletableFuture<>();

        for (Cluster<T> cluster : clusters) {
            request.setAttachment(URLParamType.version.getName(), cluster.getUrl().getVersion());
            request.setAttachment(URLParamType.application.getName(), cluster.getUrl().getApplication());
            request.setAttachment(URLParamType.module.getName(), cluster.getUrl().getModule());

            try {
                Response response = cluster.call(request);
                if (response instanceof DefaultResponseFuture responseFuture) {
                    responseFuture.setReturnType(returnType);
                    responseFuture.addListener(future -> {
                        if (future.isSuccess()) {
                            resultFuture.complete(future.getValue());
                        } else {
                            Exception ex = future.getException();
                            resultFuture.completeExceptionally(ex != null ? ex
                                    : new JawsServiceException("response future failed"));
                        }
                    });
                } else {
                    // Synchronous response (e.g., injvm or cached)
                    if (response.getException() != null) {
                        resultFuture.completeExceptionally(response.getException());
                    } else {
                        resultFuture.complete(response.getValue());
                    }
                }
                return resultFuture;
            } catch (RuntimeException e) {
                if (ExceptionUtils.isBizException(e)) {
                    Throwable t = e.getCause();
                    if (t instanceof Exception) {
                        resultFuture.completeExceptionally(t);
                    } else {
                        String msg = t == null
                                ? "biz exception cause is null, original error: " + e.getMessage()
                                : "biz exception cause is a non-Exception Throwable: "
                                        + t.getClass().getName() + ", message: " + t.getMessage();
                        resultFuture.completeExceptionally(new JawsServiceException(msg));
                    }
                } else {
                    resultFuture.completeExceptionally(e);
                }
                return resultFuture;
            }
        }

        resultFuture.completeExceptionally(new JawsServiceException(
                "Reference call Error: cluster not exists, interface=" + interfaceName + " "
                        + JawsFrameworkUtils.toString(request), JawsErrorMsgConstants.SERVICE_NOT_FOUND, false));
        return resultFuture;
    }

    private static class PrimitiveDefault {
        private static final Map<Class<?>, Object> primitiveValues = new HashMap<>();

        static {
            primitiveValues.put(boolean.class, false);
            primitiveValues.put(char.class, '\u0000');
            primitiveValues.put(byte.class, (byte) 0);
            primitiveValues.put(short.class, (short) 0);
            primitiveValues.put(int.class, 0);
            primitiveValues.put(long.class, 0L);
            primitiveValues.put(float.class, 0.0f);
            primitiveValues.put(double.class, 0.0d);
        }

        public static Object getDefaultReturnValue(Class<?> returnType) {
            return primitiveValues.get(returnType);
        }
    }
}