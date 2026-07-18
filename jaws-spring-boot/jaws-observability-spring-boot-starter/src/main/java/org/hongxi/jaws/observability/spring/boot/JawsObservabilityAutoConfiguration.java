package org.hongxi.jaws.observability.spring.boot;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.hongxi.jaws.extensions.MetricsFilter;
import org.hongxi.jaws.extensions.TracingFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Auto-Configuration for Jaws observability filters.
 * <p>
 * Automatically wires Micrometer MeterRegistry and Tracer instances
 * into the respective filters when the corresponding libraries are on the classpath.
 * <p>
 * Cross-service trace context propagation uses W3C traceparent format:
 * the consumer injects traceparent into request attachments, and the provider
 * extracts it to create a child span under the same trace.
 */
@AutoConfiguration
public class JawsObservabilityAutoConfiguration {

    @Configuration
    @ConditionalOnClass(MeterRegistry.class)
    static class MetricsConfiguration {

        private final ObjectProvider<MeterRegistry> meterRegistry;

        MetricsConfiguration(ObjectProvider<MeterRegistry> meterRegistry) {
            this.meterRegistry = meterRegistry;
        }

        @PostConstruct
        public void init() {
            MeterRegistry registry = meterRegistry.getIfAvailable();
            if (registry != null) {
                MetricsFilter.setMeterRegistry(registry);
            }
        }
    }

    @Configuration
    @ConditionalOnClass(Tracer.class)
    static class TracingConfiguration {

        private final Tracer tracer;

        TracingConfiguration(Tracer tracer) {
            this.tracer = tracer;
        }

        @PostConstruct
        public void init() {
            TracingFilter.setTracer(tracer);
        }
    }
}
