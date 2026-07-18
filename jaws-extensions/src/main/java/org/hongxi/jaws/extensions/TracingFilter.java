package org.hongxi.jaws.extensions;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.hongxi.jaws.common.extension.Activation;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.filter.Filter;
import org.hongxi.jaws.rpc.Caller;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Distributed tracing filter using Micrometer Tracer API directly.
 * <p>
 * Uses W3C Trace Context format (traceparent) for cross-service propagation.
 * Creates spans via {@link Tracer} directly, ensuring span creation and context
 * propagation work regardless of ObservationHandler registration.
 * <p>
 * Consumer side: creates span → injects traceparent into request attachments.
 * Provider side: extracts traceparent → creates child span under same trace.
 */
@SpiMeta(name = "tracing")
@Activation(key = {"service", "reference"}, sequence = 5)
public class TracingFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(TracingFilter.class);
    private static final String TRACEPARENT_KEY = "traceparent";

    private static volatile Tracer tracer;

    public static void setTracer(Tracer tracer) {
        log.info("[Jaws-Tracing] Tracer injected: {}", tracer.getClass().getName());
        TracingFilter.tracer = tracer;
    }

    public static Tracer getTracer() {
        return tracer;
    }

    @Override
    public Response filter(Caller<?> caller, Request request) {
        Tracer t = tracer;
        if (t == null) {
            log.warn("[Jaws-Tracing] Tracer is null, skipping tracing for: {}", request.getInterfaceName() + "." + request.getMethodName());
            return caller.call(request);
        }

        boolean isProvider = caller instanceof Provider;
        String spanName = request.getInterfaceName() + "." + request.getMethodName();
        log.debug("[Jaws-Tracing] Filter invoked: side={}, span={}", isProvider ? "provider" : "consumer", spanName);

        if (isProvider) {
            return handleProvider(t, caller, request, spanName);
        } else {
            return handleConsumer(t, caller, request, spanName);
        }
    }

    /**
     * Consumer (client) side:
     * 1. Create a new span (automatically child of current span if any, e.g. HTTP span)
     * 2. Open scope to make it current
     * 3. Inject traceparent into request attachments
     * 4. Execute the RPC call
     */
    private Response handleConsumer(Tracer t, Caller<?> caller, Request request, String spanName) {
        Span span = t.nextSpan().name(spanName).start();
        try (Tracer.SpanInScope scope = t.withSpan(span)) {
            injectTraceparent(t, request);
            log.debug("[Jaws-Tracing] Consumer span created: spanName={}, traceId={}, spanId={}",
                    spanName, span.context().traceId(), span.context().spanId());
            Response response = caller.call(request);
            if (response.getException() != null) {
                span.error(response.getException());
            }
            return response;
        } catch (Exception e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Provider (server) side:
     * 1. Extract traceparent from request attachments
     * 2. Build parent TraceContext from traceparent
     * 3. Create child span under the extracted parent context
     * 4. Open scope and execute business logic
     */
    private Response handleProvider(Tracer t, Caller<?> caller, Request request, String spanName) {
        String traceparent = request.getAttachments().get(TRACEPARENT_KEY);
        Span span;
        if (traceparent != null && !traceparent.isEmpty()) {
            TraceContext parentContext = parseTraceparent(t, traceparent);
            if (parentContext != null) {
                span = t.spanBuilder().setParent(parentContext).name(spanName).start();
                log.debug("[Jaws-Tracing] Provider child span created: spanName={}, traceId={}, spanId={}, parentTraceparent={}",
                        spanName, span.context().traceId(), span.context().spanId(), traceparent);
            } else {
                span = t.nextSpan().name(spanName).start();
                log.debug("[Jaws-Tracing] Provider span created (parse failed): spanName={}, traceparent={}",
                        spanName, traceparent);
            }
        } else {
            span = t.nextSpan().name(spanName).start();
            log.debug("[Jaws-Tracing] Provider span created (no traceparent): spanName={}", spanName);
        }

        try (Tracer.SpanInScope scope = t.withSpan(span)) {
            Response response = caller.call(request);
            if (response.getException() != null) {
                span.error(response.getException());
            }
            return response;
        } catch (Exception e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Inject current span's trace context into request attachments as W3C traceparent.
     * Format: 00-{traceId}-{spanId}-{traceFlags}
     */
    private void injectTraceparent(Tracer t, Request request) {
        Span currentSpan = t.currentSpan();
        if (currentSpan == null) {
            log.warn("[Jaws-Tracing] No current span found for traceparent injection");
            return;
        }
        TraceContext ctx = currentSpan.context();
        String traceId = ctx.traceId();
        String spanId = ctx.spanId();
        if (traceId == null || spanId == null) {
            log.warn("[Jaws-Tracing] TraceId or SpanId is null: traceId={}, spanId={}", traceId, spanId);
            return;
        }
        Boolean sampled = ctx.sampled();
        String traceFlags = (sampled != null && sampled) ? "01" : "00";
        String traceparent = "00-" + traceId + "-" + spanId + "-" + traceFlags;
        request.setAttachment(TRACEPARENT_KEY, traceparent);
        log.debug("[Jaws-Tracing] Injected traceparent: {}", traceparent);
    }

    /**
     * Parse W3C traceparent header and build a TraceContext via the Tracer.
     */
    private TraceContext parseTraceparent(Tracer t, String traceparent) {
        // Format: 00-{traceId}-{spanId}-{traceFlags}
        String[] parts = traceparent.split("-");
        if (parts.length < 4) {
            log.warn("[Jaws-Tracing] Invalid traceparent format: {}", traceparent);
            return null;
        }
        try {
            return t.traceContextBuilder()
                    .traceId(parts[1])
                    .spanId(parts[2])
                    .sampled("01".equals(parts[3]))
                    .build();
        } catch (Exception e) {
            log.warn("[Jaws-Tracing] Failed to parse traceparent: {}", traceparent, e);
            return null;
        }
    }
}
