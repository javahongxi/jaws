package org.hongxi.jaws.extensions;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.filter.Filter;
import org.hongxi.jaws.rpc.Caller;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Distributed tracing filter using Micrometer Tracer and Propagator APIs.
 * <p>
 * Uses {@link Propagator} for cross-service trace context propagation,
 * supporting W3C Trace Context (traceparent) and other formats transparently.
 * <p>
 * Consumer side: creates span → propagator injects trace context into request attachments.
 * Provider side: propagator extracts trace context → creates child span under same trace.
 */
@Extension(value = "tracing", order = 5, keys = {"service", "reference"})
public class TracingFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(TracingFilter.class);

    private static volatile Tracer tracer;
    private static volatile Propagator propagator;

    public static void setTracer(Tracer tracer) {
        log.info("[Jaws-Tracing] Tracer injected: {}", tracer.getClass().getName());
        TracingFilter.tracer = tracer;
    }

    public static void setPropagator(Propagator propagator) {
        log.info("[Jaws-Tracing] Propagator injected: {}", propagator.getClass().getName());
        TracingFilter.propagator = propagator;
    }

    @Override
    public CompletableFuture<Response> filter(Caller<?> caller, Request request) {
        Tracer t = tracer;
        Propagator p = propagator;
        if (t == null || p == null) {
            return caller.callAsync(request);
        }

        boolean isProvider = caller instanceof Provider;
        String spanName = request.getInterfaceName() + "." + request.getMethodName();
        log.debug("[Jaws-Tracing] Filter invoked: side={}, span={}", isProvider ? "provider" : "consumer", spanName);

        if (isProvider) {
            return handleProvider(t, p, caller, request, spanName);
        } else {
            return handleConsumer(t, p, caller, request, spanName);
        }
    }

    /**
     * Consumer (client) side:
     * 1. Create a new span (automatically child of current span if any)
     * 2. Use Propagator to inject trace context into request attachments
     * 3. Execute the async RPC call
     * 4. On completion, record errors if any and end the span
     */
    private CompletableFuture<Response> handleConsumer(Tracer t, Propagator p, Caller<?> caller, Request request, String spanName) {
        Span span = t.nextSpan().name(spanName).start();
        try (Tracer.SpanInScope scope = t.withSpan(span)) {
            p.inject(span.context(), request, Request::setAttachment);
            log.debug("[Jaws-Tracing] Consumer span created: spanName={}, traceId={}, spanId={}",
                    spanName, span.context().traceId(), span.context().spanId());
        }
        return caller.callAsync(request).whenComplete((response, throwable) -> {
            if (throwable != null) {
                span.error(throwable);
            } else if (response != null && response.getException() != null) {
                span.error(response.getException());
            }
            span.end();
        });
    }

    /**
     * Provider (server) side:
     * 1. Use Propagator to extract trace context from request attachments
     * 2. Create child span from the extracted context
     * 3. Execute the async call
     * 4. On completion, record errors if any and end the span
     */
    private CompletableFuture<Response> handleProvider(Tracer t, Propagator p, Caller<?> caller, Request request, String spanName) {
        Span span = p.extract(request, (req, key) -> req.getAttachments().get(key))
                .name(spanName)
                .start();
        try (Tracer.SpanInScope scope = t.withSpan(span)) {
            log.debug("[Jaws-Tracing] Provider child span created: spanName={}, traceId={}, spanId={}",
                    spanName, span.context().traceId(), span.context().spanId());
        }
        return caller.callAsync(request).whenComplete((response, throwable) -> {
            if (throwable != null) {
                span.error(throwable);
            } else if (response != null && response.getException() != null) {
                span.error(response.getException());
            }
            span.end();
        });
    }
}
