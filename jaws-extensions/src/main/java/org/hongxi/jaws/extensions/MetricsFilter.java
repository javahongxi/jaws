package org.hongxi.jaws.extensions;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.hongxi.jaws.common.extension.Activation;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.filter.Filter;
import org.hongxi.jaws.rpc.Caller;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer-based metrics collection filter.
 * <p>
 * Collects the following metrics:
 * <ul>
 *   <li>jaws.rpc.requests - Total number of RPC calls (counter)</li>
 *   <li>jaws.rpc.success - Total number of successful calls (counter)</li>
 *   <li>jaws.rpc.failure - Total number of failed calls (counter)</li>
 *   <li>jaws.rpc.duration - Latency histogram (timer)</li>
 *   <li>jaws.rpc.active - Number of in-flight requests (gauge via long task timer)</li>
 * </ul>
 * <p>
 * Tags: application, service, method, side (provider/consumer)
 */
@SpiMeta(name = "metrics")
@Activation(key = "service", sequence = 10)
public class MetricsFilter implements Filter {

    private static final String METRIC_PREFIX = "jaws.rpc.";
    private static final String TAG_APPLICATION = "application";
    private static final String TAG_SERVICE = "service";
    private static final String TAG_METHOD = "method";
    private static final String TAG_SIDE = "side";
    private static final String TAG_STATUS = "status";

    private static volatile MeterRegistry meterRegistry;

    /**
     * Set the global MeterRegistry. Must be called before the filter is used.
     * Typically called during application startup.
     *
     * @param registry the MeterRegistry to use for recording metrics
     */
    public static void setMeterRegistry(MeterRegistry registry) {
        meterRegistry = registry;
    }

    public static MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }

    @Override
    public Response filter(Caller<?> caller, Request request) {
        MeterRegistry registry = meterRegistry;
        if (registry == null) {
            // No registry configured, skip metrics collection
            return caller.call(request);
        }

        String application = caller.getUrl().getApplication();
        String service = simplifyClassName(request.getInterfaceName());
        String method = request.getMethodName();
        String side = caller instanceof Provider ? "provider" : "consumer";

        // Record active requests
        registry.summary(METRIC_PREFIX + "active",
                TAG_APPLICATION, application,
                TAG_SERVICE, service,
                TAG_METHOD, method,
                TAG_SIDE, side
        ).record(1);

        long startTime = System.nanoTime();
        boolean success = false;
        try {
            Response response = caller.call(request);
            success = true;
            return response;
        } finally {
            long elapsed = System.nanoTime() - startTime;

            // Decrement active requests
            registry.summary(METRIC_PREFIX + "active",
                    TAG_APPLICATION, application,
                    TAG_SERVICE, service,
                    TAG_METHOD, method,
                    TAG_SIDE, side
            ).record(-1);

            // Record total requests
            Counter.builder(METRIC_PREFIX + "requests")
                    .tag(TAG_APPLICATION, application)
                    .tag(TAG_SERVICE, service)
                    .tag(TAG_METHOD, method)
                    .tag(TAG_SIDE, side)
                    .register(registry)
                    .increment();

            // Record success or failure
            String status = success ? "success" : "failure";
            Counter.builder(METRIC_PREFIX + (success ? "success" : "failure"))
                    .tag(TAG_APPLICATION, application)
                    .tag(TAG_SERVICE, service)
                    .tag(TAG_METHOD, method)
                    .tag(TAG_SIDE, side)
                    .register(registry)
                    .increment();

            // Record duration
            Timer.builder(METRIC_PREFIX + "duration")
                    .tag(TAG_APPLICATION, application)
                    .tag(TAG_SERVICE, service)
                    .tag(TAG_METHOD, method)
                    .tag(TAG_SIDE, side)
                    .tag(TAG_STATUS, status)
                    .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                    .register(registry)
                    .record(elapsed, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Simplify fully qualified class name to simple name.
     */
    private String simplifyClassName(String fullName) {
        if (fullName == null || fullName.isEmpty()) {
            return "unknown";
        }
        int lastDot = fullName.lastIndexOf('.');
        return lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
    }
}
