package org.hongxi.jaws.cluster.ha;

import org.hongxi.jaws.cluster.LoadBalance;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.common.util.ExceptionUtils;
import org.hongxi.jaws.config.configcenter.DynamicConfiguration;
import org.hongxi.jaws.config.configcenter.DynamicConfigurationKeys;
import org.hongxi.jaws.config.configcenter.DynamicConfigurationUtils;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Failover ha strategy.
 * <p>
 * Created by shenhongxi on 2021/4/23.
 */
@SpiMeta(name = "failover")
public class FailoverHaStrategy<T> extends AbstractHaStrategy<T> {

    private static final Logger log = LoggerFactory.getLogger(FailoverHaStrategy.class);

    protected ThreadLocal<List<Reference<T>>> referencesHolder = ThreadLocal.withInitial(ArrayList::new);

    @Override
    public Response call(Request request, LoadBalance<T> loadBalance) {

        List<Reference<T>> references = selectReferences(request, loadBalance);
        if (references.isEmpty()) {
            throw new JawsServiceException(String.format("FailoverHaStrategy No references for request:%s, loadbalance:%s", request,
                    loadBalance));
        }
        URL refUrl = references.get(0).getUrl();
        // Resolve retries with dynamic configuration priority:
        // method-level dynamic > service-level dynamic > global dynamic > URL config
        int urlRetries =
                refUrl.getMethodParameter(request.getMethodName(), request.getParamDesc(), URLParamType.retries.getName(),
                        URLParamType.retries.intValue());
        int tryCount = resolveRetries(request, urlRetries);
        // 如果有问题，则设置为不重试
        if (tryCount < 0) {
            tryCount = 0;
        }

        for (int i = 0; i <= tryCount; i++) {
            Reference<T> refer = references.get(i % references.size());
            try {
                request.setRetries(i);
                RpcContext.getContext().setServerUrl(refer.getUrl());
                return refer.call(request);
            } catch (RuntimeException e) {
                // 对于业务异常，直接抛出
                if (ExceptionUtils.isBizException(e)) {
                    throw e;
                } else if (i >= tryCount) {
                    throw e;
                }
                log.warn("FailoverHaStrategy Call false for request: {}", request, e);
            }
        }

        throw new JawsFrameworkException("FailoverHaStrategy.call should not come here!");
    }

    /**
     * Resolve retries from dynamic configuration with fallback chain:
     * method-level key -> service-level key -> global key -> URL default.
     */
    private int resolveRetries(Request request, int urlDefault) {
        DynamicConfiguration dc = DynamicConfigurationUtils.getDynamicConfiguration();
        if (!dc.hasAnyConfig()) {
            return urlDefault;
        }
        String interfaceName = request.getInterfaceName();
        String methodName = request.getMethodName();

        // method-level dynamic override
        int val = dc.getIntConfig(DynamicConfigurationKeys.retries(interfaceName, methodName), Integer.MIN_VALUE);
        if (val != Integer.MIN_VALUE) {
            return val;
        }
        // service-level dynamic override
        val = dc.getIntConfig(DynamicConfigurationKeys.retries(interfaceName), Integer.MIN_VALUE);
        if (val != Integer.MIN_VALUE) {
            return val;
        }
        // global dynamic override
        val = dc.getIntConfig(DynamicConfigurationKeys.GLOBAL_RETRIES, Integer.MIN_VALUE);
        if (val != Integer.MIN_VALUE) {
            return val;
        }
        return urlDefault;
    }

    protected List<Reference<T>> selectReferences(Request request, LoadBalance<T> loadBalance) {
        List<Reference<T>> references = referencesHolder.get();
        references.clear();
        loadBalance.selectToHolder(request, references);
        return references;
    }
}
