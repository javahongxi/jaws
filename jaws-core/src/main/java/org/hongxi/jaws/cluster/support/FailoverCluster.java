package org.hongxi.jaws.cluster.support;

import org.hongxi.jaws.cluster.LoadBalance;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.common.util.ExceptionUtils;
import org.hongxi.jaws.common.util.RpcUtils;
import org.hongxi.jaws.configcenter.DynamicConfigurationKeys;
import org.hongxi.jaws.configcenter.DynamicConfigurationUtils;
import org.hongxi.jaws.exception.JawsErrorCode;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.exception.JawsAbstractException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Failover cluster: retries on failure by selecting a different reference.
 * <p>
 * The number of retries is resolved with dynamic configuration priority:
 * method-level dynamic &gt; service-level dynamic &gt; global dynamic &gt; URL config.
 */
@Extension("failover")
public class FailoverCluster<T> extends AbstractCluster<T> {

    private static final Logger log = LoggerFactory.getLogger(FailoverCluster.class);

    public FailoverCluster(URL url, LoadBalance<T> loadBalance) {
        super(url, loadBalance);
    }

    @Override
    public Response call(Request request) {
        if (!available.get()) {
            throw new JawsServiceException("Cluster not available, interface=" + getInterface(),
                    JawsErrorCode.SERVICE_NOT_FOUND, false);
        }

        try {
            return doCall(request);
        } catch (Exception e) {
            return handleException(request, e);
        }
    }

    private Response doCall(Request request) {
        List<Reference<T>> references = selectReferences(request);
        if (references.isEmpty()) {
            throw new JawsServiceException(
                    String.format("FailoverCluster No references for request:%s, loadBalance:%s",
                            request, loadBalance));
        }
        URL refUrl = references.get(0).getUrl();
        // Resolve retries with dynamic configuration priority:
        // method-level dynamic > service-level dynamic > global dynamic > URL config
        int urlRetries = refUrl.getMethodParameter(request.getMethodName(), request.getParamDesc(),
                UrlParam.Cluster.RETRIES.getName(), UrlParam.Cluster.RETRIES.intValue());
        int tryCount = resolveRetries(request, urlRetries);
        if (tryCount < 0) {
            tryCount = 0;
        }

        for (int i = 0; i <= tryCount; i++) {
            Reference<T> refer = references.get(i % references.size());
            try {
                ((DefaultRequest) request).setRetries(i);
                RpcContext.getContext().setServerUrl(refer.getUrl());
                return refer.call(request);
            } catch (RuntimeException e) {
                if (ExceptionUtils.isBizException(e)) {
                    throw e;
                } else if (i >= tryCount) {
                    throw e;
                }
                log.warn("FailoverCluster call failed, retrying: {}", request, e);
            }
        }

        throw new JawsFrameworkException("FailoverCluster.call should never reach here after the retry loop");
    }

    /**
     * Resolve retries from dynamic configuration with fallback chain:
     * method-level key -> service-level key -> global key -> URL default.
     */
    private int resolveRetries(Request request, int urlDefault) {
        String interfaceName = request.getInterfaceName();
        String methodName = request.getMethodName();
        return DynamicConfigurationUtils.resolveIntConfig(urlDefault,
                DynamicConfigurationKeys.retries(interfaceName, methodName),
                DynamicConfigurationKeys.retries(interfaceName),
                DynamicConfigurationKeys.GLOBAL_RETRIES);
    }

    private List<Reference<T>> selectReferences(Request request) {
        return loadBalance.selectCandidates(request);
    }

    private Response handleException(Request request, Exception e) {
        if (ExceptionUtils.isBizException(e)) {
            throw (RuntimeException) e;
        }
        if (!url.getBoolParameter(UrlParam.Client.THROW_EXCEPTION)) {
            return RpcUtils.buildErrorResponse(request, e);
        }
        if (e instanceof JawsAbstractException jae) {
            throw jae;
        }
        throw new JawsServiceException("FailoverCluster call failed, request=" + request, e);
    }

    @Override
    public CompletableFuture<Response> callAsync(Request request) {
        if (!available.get()) {
            return CompletableFuture.failedFuture(new JawsServiceException(
                    "Cluster not available, interface=" + getInterface(),
                    JawsErrorCode.SERVICE_NOT_FOUND, false));
        }

        List<Reference<T>> references = selectReferences(request);
        if (references.isEmpty()) {
            return CompletableFuture.completedFuture(RpcUtils.buildErrorResponse(request,
                    new JawsServiceException(String.format(
                            "FailoverCluster No references for request:%s, loadBalance:%s",
                            request, loadBalance))));
        }

        URL refUrl = references.get(0).getUrl();
        int urlRetries = refUrl.getMethodParameter(request.getMethodName(), request.getParamDesc(),
                UrlParam.Cluster.RETRIES.getName(), UrlParam.Cluster.RETRIES.intValue());
        int tryCount = resolveRetries(request, urlRetries);
        if (tryCount < 0) {
            tryCount = 0;
        }

        return doCallAsync(request, references, tryCount, 0);
    }

    private CompletableFuture<Response> doCallAsync(Request request, List<Reference<T>> references,
                                                     int maxRetries, int attempt) {
        Reference<T> refer = references.get(attempt % references.size());
        ((DefaultRequest) request).setRetries(attempt);
        RpcContext.getContext().setServerUrl(refer.getUrl());

        CompletableFuture<Response> responseFuture;
        try {
            responseFuture = refer.callAsync(request);
        } catch (Exception e) {
            responseFuture = CompletableFuture.completedFuture(
                    RpcUtils.buildErrorResponse(request, e));
        }

        return responseFuture.thenCompose(response -> {
            Throwable throwable = response.getThrowable();
            if (throwable == null) {
                return CompletableFuture.completedFuture(response);
            }
            if (ExceptionUtils.isBizException(throwable) || attempt >= maxRetries) {
                return CompletableFuture.completedFuture(response);
            }
            log.warn("FailoverCluster callAsync failed, retrying: {}", request, throwable);
            return doCallAsync(request, references, maxRetries, attempt + 1);
        });
    }
}
