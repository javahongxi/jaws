package org.hongxi.jaws.extensions;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.hongxi.jaws.common.extension.Activation;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.filter.Filter;
import org.hongxi.jaws.rpc.Caller;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;

/**
 * OpenTelemetry-based distributed tracing filter.
 * <p>
 * Creates spans for each RPC call with the following attributes:
 * <ul>
 *   <li>rpc.system = "jaws"</li>
 *   <li>rpc.service - the interface name</li>
 *   <li>rpc.method - the method name</li>
 *   <li>rpc.application - the application name</li>
 *   <li>net.peer.name / net.peer.port - remote address</li>
 * </ul>
 * <p>
 * Propagates context via request attachments for cross-service trace correlation.
 */
@SpiMeta(name = "tracing")
@Activation(key = "service", sequence = 5)
public class TracingFilter implements Filter {

    private static final String INSTRUMENTATION_NAME = "jaws-rpc";
    private static final String ATTR_RPC_SYSTEM = "rpc.system";
    private static final String ATTR_RPC_SERVICE = "rpc.service";
    private static final String ATTR_RPC_METHOD = "rpc.method";
    private static final String ATTR_RPC_APPLICATION = "rpc.application";
    private static final String ATTR_NET_PEER_NAME = "net.peer.name";
    private static final String ATTR_NET_PEER_PORT = "net.peer.port";

    // Keys for context propagation in request attachments
    private static final String TRACE_CONTEXT_PREFIX = "otel.";

    private static volatile OpenTelemetry openTelemetry;

    /**
     * Set the global OpenTelemetry instance. If not set, uses GlobalOpenTelemetry.get().
     *
     * @param otel the OpenTelemetry instance to use
     */
    public static void setOpenTelemetry(OpenTelemetry otel) {
        openTelemetry = otel;
    }

    public static OpenTelemetry getOpenTelemetry() {
        return openTelemetry;
    }

    @Override
    public Response filter(Caller<?> caller, Request request) {
        OpenTelemetry otel = openTelemetry;
        if (otel == null) {
            try {
                otel = GlobalOpenTelemetry.get();
            } catch (Exception e) {
                // OpenTelemetry not initialized, skip tracing
                return caller.call(request);
            }
        }

        Tracer tracer = otel.getTracer(INSTRUMENTATION_NAME);
        boolean isProvider = caller instanceof Provider;
        SpanKind spanKind = isProvider ? SpanKind.SERVER : SpanKind.CLIENT;

        Span span;
        Context parentContext = Context.current();

        // For provider (server), extract context from incoming request attachments
        if (isProvider) {
            parentContext = otel.getPropagators().getTextMapPropagator()
                    .extract(Context.current(), request.getAttachments(), ATTACHMENT_GETTER);
        }

        String spanName = request.getInterfaceName() + "." + request.getMethodName();
        span = tracer.spanBuilder(spanName)
                .setSpanKind(spanKind)
                .setParent(parentContext)
                .startSpan();

        // Set span attributes
        AttributesBuilder attrBuilder = Attributes.builder();
        attrBuilder.put(ATTR_RPC_SYSTEM, "jaws");
        attrBuilder.put(ATTR_RPC_SERVICE, request.getInterfaceName());
        attrBuilder.put(ATTR_RPC_METHOD, request.getMethodName());
        attrBuilder.put(ATTR_RPC_APPLICATION, caller.getUrl().getApplication());

        if (isProvider) {
            // Server side: record client info from request
            String clientHost = request.getAttachments().get("host");
            if (clientHost != null) {
                attrBuilder.put(ATTR_NET_PEER_NAME, clientHost);
            }
        } else {
            // Client side: record target server info
            attrBuilder.put(ATTR_NET_PEER_NAME, caller.getUrl().getHost());
            attrBuilder.put(ATTR_NET_PEER_PORT, caller.getUrl().getPort());
        }
        span.setAllAttributes(attrBuilder.build());

        // For consumer (client), inject context into outgoing request attachments
        if (!isProvider) {
            injectContext(otel, span, request);
        }

        // Execute the call within the span scope
        try (var scope = span.makeCurrent()) {
            Response response = caller.call(request);

            // Check for business exception
            if (response.getException() != null) {
                span.setStatus(StatusCode.ERROR, response.getException().getMessage());
                span.recordException(response.getException());
            } else {
                span.setStatus(StatusCode.OK);
            }
            return response;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * TextMapGetter for extracting trace context from request attachments.
     */
    private static final TextMapGetter<java.util.Map<String, String>> ATTACHMENT_GETTER =
            new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(java.util.Map<String, String> attachments) {
                    return attachments.keySet();
                }

                @Override
                public String get(java.util.Map<String, String> attachments, String key) {
                    return attachments.get(TRACE_CONTEXT_PREFIX + key);
                }
            };

    /**
     * TextMapSetter for injecting trace context into request attachments.
     * Uses Request.setAttachment() to avoid issues with immutable maps.
     */
    private static final TextMapSetter<java.util.Map<String, String>> ATTACHMENT_SETTER =
            (attachments, key, value) -> {
                // This setter is used with request.getAttachments() which may be immutable.
                // The actual injection is done via request.setAttachment() in the filter method.
                // This setter is a no-op placeholder; see injectContext() below.
            };

    /**
     * Inject trace context into request using setAttachment (safe for mutable/immutable maps).
     */
    private void injectContext(OpenTelemetry otel, Span span, Request request) {
        otel.getPropagators().getTextMapPropagator()
                .inject(Context.current().with(span), request, REQUEST_SETTER);
    }

    private static final TextMapSetter<Request> REQUEST_SETTER =
            (request, key, value) -> {
                if (value != null && request != null) {
                    request.setAttachment(TRACE_CONTEXT_PREFIX + key, value);
                }
            };

}
