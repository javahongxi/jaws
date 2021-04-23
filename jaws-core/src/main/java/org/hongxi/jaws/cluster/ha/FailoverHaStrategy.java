package org.hongxi.jaws.cluster.ha;

import org.hongxi.jaws.cluster.LoadBalance;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.common.util.ExceptionUtils;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.Referer;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Failover ha strategy.
 *
 * Created by shenhongxi on 2021/4/23.
 */
@SpiMeta(name = "failover")
public class FailoverHaStrategy<T> extends AbstractHaStrategy<T> {

    private static final Logger log = LoggerFactory.getLogger(FailoverHaStrategy.class);

    protected ThreadLocal<List<Referer<T>>> referersHolder = ThreadLocal.withInitial(ArrayList::new);

    @Override
    public Response call(Request request, LoadBalance<T> loadBalance) {

        List<Referer<T>> referers = selectReferers(request, loadBalance);
        if (referers.isEmpty()) {
            throw new JawsServiceException(String.format("FailoverHaStrategy No referers for request:%s, loadbalance:%s", request,
                    loadBalance));
        }
        URL refUrl = referers.get(0).getUrl();
        // 先使用method的配置
        int tryCount =
                refUrl.getMethodParameter(request.getMethodName(), request.getParametersDesc(), URLParamType.retries.getName(),
                        URLParamType.retries.intValue());
        // 如果有问题，则设置为不重试
        if (tryCount < 0) {
            tryCount = 0;
        }

        for (int i = 0; i <= tryCount; i++) {
            Referer<T> refer = referers.get(i % referers.size());
            try {
                request.setRetries(i);
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

    protected List<Referer<T>> selectReferers(Request request, LoadBalance<T> loadBalance) {
        List<Referer<T>> referers = referersHolder.get();
        referers.clear();
        loadBalance.selectToHolder(request, referers);
        return referers;
    }
}
