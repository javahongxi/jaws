package org.hongxi.jaws.cluster.support;

import org.hongxi.jaws.cluster.LoadBalance;
import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.common.util.ExceptionUtils;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Failsafe cluster: catches all exceptions and returns an empty response,
 * logging the error instead of propagating it. Suitable for non-critical
 * operations (e.g. audit logging, metrics reporting) where failure must
 * not affect the caller.
 */
@Extension("failsafe")
public class FailsafeCluster<T> extends AbstractCluster<T> {

    private static final Logger log = LoggerFactory.getLogger(FailsafeCluster.class);

    public FailsafeCluster(URL url, LoadBalance<T> loadBalance) {
        super(url, loadBalance);
    }

    @Override
    public Response call(Request request) {
        if (!available.get()) {
            log.warn("FailsafeCluster: cluster not available, interface={}, returning empty response",
                    getInterface());
            return new DefaultResponse(request.getRequestId());
        }

        try {
            Reference<T> refer = loadBalance.select(request);
            RpcContext.getContext().setServerUrl(refer.getUrl());
            return refer.call(request);
        } catch (Exception e) {
            if (ExceptionUtils.isBizException(e)) {
                throw (RuntimeException) e;
            }
            log.warn("FailsafeCluster: call failed, returning empty response, request={}", request, e);
            return new DefaultResponse(request.getRequestId());
        }
    }

    @Override
    public CompletableFuture<Response> callAsync(Request request) {
        if (!available.get()) {
            log.warn("FailsafeCluster: cluster not available, interface={}, returning empty response",
                    getInterface());
            return CompletableFuture.completedFuture(new DefaultResponse(request.getRequestId()));
        }

        Reference<T> refer = loadBalance.select(request);
        RpcContext.getContext().setServerUrl(refer.getUrl());
        return refer.callAsync(request).exceptionally(ex -> {
            if (ExceptionUtils.isBizException(ex)) {
                throw ex instanceof RuntimeException re ? re : new RuntimeException(ex);
            }
            log.warn("FailsafeCluster: callAsync failed, returning empty response, request={}", request, ex);
            return new DefaultResponse(request.getRequestId());
        });
    }
}
