package org.hongxi.jaws.transport;

import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.hongxi.jaws.config.configcenter.DynamicConfiguration;
import org.hongxi.jaws.config.configcenter.DynamicConfigurationKeys;
import org.hongxi.jaws.config.configcenter.DynamicConfigurationUtils;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p>
 * provider 消息处理分发：支持一定程度的自我防护
 * <p>
 * <pre>
 *
 * 		1) 如果接口只有一个方法，那么直接return true
 * 		2) 如果接口有多个方法，那么如果单个method超过 maxThread / 2 && totalCount >  (maxThread * 3 / 4)，那么return false;
 * 		3) 如果接口有多个方法(4个)，同时总的请求数超过 maxThread * 3 / 4，同时该method的请求数超过 maxThead * 1 / 4， 那么return false
 * 		4) 其他场景return true
 *
 * </pre>
 * <p>
 * Created by shenhongxi on 2021/4/22.
 */
@SpiMeta(name = "jaws")
public class DefaultProtectedStrategy implements ProviderProtectedStrategy {

    private static final Logger log = LoggerFactory.getLogger(DefaultProtectedStrategy.class);

    protected ConcurrentMap<String, AtomicInteger> requestCounters = new ConcurrentHashMap<>();
    protected ConcurrentMap<String, AtomicInteger> rejectCounters = new ConcurrentHashMap<>();
    protected AtomicInteger totalCounter = new AtomicInteger(0);
    protected AtomicInteger rejectCounter = new AtomicInteger(0);
    protected AtomicInteger methodCounter = new AtomicInteger(1);

    @Override
    public void setMethodCounter(AtomicInteger methodCounter) {
        this.methodCounter = methodCounter;
    }

    @Override
    public Response call(Request request, Provider<?> provider) {
        // Resolve maxWorkerThreads with dynamic configuration priority:
        // service-level dynamic > global dynamic > URL config
        int urlMaxThread = provider.getUrl().getParameter(URLParamType.maxWorkerThreads.getName(), URLParamType.maxWorkerThreads.intValue());
        int maxThread = resolveMaxWorkerThreads(request.getInterfaceName(), urlMaxThread);

        String requestKey = JawsFrameworkUtils.getFullMethodString(request);

        try {
            int requestCounter = incrCounter(requestKey, requestCounters);
            int currentTotal = incrTotalCounter();
            if (isAllowRequest(requestCounter, currentTotal, maxThread)) {
                return provider.call(request);
            } else {
                // reject request
                return reject(request.getInterfaceName() + "." + request.getMethodName(), requestCounter, currentTotal, maxThread, request);
            }
        } finally {
            decrTotalCounter();
            decrCounter(requestKey, requestCounters);
        }
    }

    private Response reject(String method, int requestCounter, int currentTotal, int maxThread, Request request) {
        String message = "ThreadProtectedRequestRouter reject request: request_method=" + method + " request_counter=" + requestCounter
                + " total_counter=" + currentTotal + " max_thread=" + maxThread;
        JawsServiceException exception = new JawsServiceException(message, JawsErrorMsgConstants.SERVICE_REJECT, false);
        DefaultResponse response = JawsFrameworkUtils.buildErrorResponse(request, exception);
        log.error(exception.getMessage());
        incrCounter(method, rejectCounters);
        rejectCounter.incrementAndGet();
        return response;
    }

    private int incrCounter(String requestKey, ConcurrentMap<String, AtomicInteger> counters) {
        AtomicInteger counter = counters.get(requestKey);
        if (counter == null) {
            counter = new AtomicInteger(0);
            counters.putIfAbsent(requestKey, counter);
            counter = counters.get(requestKey);
        }
        return counter.incrementAndGet();
    }

    private int decrCounter(String requestKey, ConcurrentMap<String, AtomicInteger> counters) {
        AtomicInteger counter = counters.get(requestKey);
        if (counter == null) {
            return 0;
        }
        return counter.decrementAndGet();
    }

    private int incrTotalCounter() {
        return totalCounter.incrementAndGet();
    }

    private int decrTotalCounter() {
        return totalCounter.decrementAndGet();
    }

    public boolean isAllowRequest(int requestCounter, int currentTotal, int maxThread) {

        // 方法总数为1或该方法第一次请求, 直接return true
        if (methodCounter.get() == 1 || requestCounter == 1) {
            return true;
        }

        // 不简单判断 requsetCount > (maxThread / 2) ，因为假如有2或者3个method对外提供，
        // 但是只有一个接口很大调用量，而其他接口很空闲，那么这个时候允许单个method的极限到 maxThread * 3 / 4
        if (requestCounter > (maxThread / 2) && currentTotal > (maxThread * 3 / 4)) {
            return false;
        }

        // 如果总体线程数超过 maxThread * 3 / 4个，并且对外的method比较多，那么意味着这个时候整体压力比较大，
        // 那么这个时候如果单method超过 maxThread * 1 / 4，那么reject
        return !(methodCounter.get() >= 4 && currentTotal > (maxThread * 3 / 4) && requestCounter > (maxThread / 4));
    }

    /**
     * Resolve maxWorkerThreads from dynamic configuration with fallback chain:
     * service-level key -> global key -> URL default.
     */
    private int resolveMaxWorkerThreads(String interfaceName, int urlDefault) {
        DynamicConfiguration dc = DynamicConfigurationUtils.getDynamicConfiguration();
        if (!dc.hasAnyConfig()) {
            return urlDefault;
        }
        // service-level dynamic override
        int val = dc.getIntConfig(DynamicConfigurationKeys.maxWorkerThreads(interfaceName), 0);
        if (val > 0) {
            return val;
        }
        // global dynamic override
        val = dc.getIntConfig(DynamicConfigurationKeys.GLOBAL_MAX_WORKER_THREADS, 0);
        if (val > 0) {
            return val;
        }
        return urlDefault;
    }
}