package org.hongxi.jaws.observability.spring.boot;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
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
 * Cross-service trace context propagation uses {@link Propagator} to inject
 * and extract trace context via request attachments, supporting W3C Trace Context
 * and other formats transparently.
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
    @ConditionalOnClass({Tracer.class, Propagator.class})
    static class TracingConfiguration {

        private final Tracer tracer;
        private final Propagator propagator;

        TracingConfiguration(Tracer tracer, Propagator propagator) {
            this.tracer = tracer;
            this.propagator = propagator;
        }

        @PostConstruct
        public void init() {
            TracingFilter.setTracer(tracer);
            TracingFilter.setPropagator(propagator);
        }
    }
}
