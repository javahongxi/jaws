package org.hongxi.jaws.observability.spring.boot;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.hongxi.jaws.extensions.MetricsFilter;
import org.hongxi.jaws.extensions.TracingFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Auto-configuration for Jaws observability filters.
 * <p>
 * Automatically wires Micrometer MeterRegistry and OpenTelemetry instances
 * into the respective filters when the corresponding libraries are on the classpath.
 * <p>
 * Usage: simply add jaws-observability + micrometer/opentelemetry dependencies,
 * and the filters will be automatically configured via SPI.
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
    @ConditionalOnClass(OpenTelemetry.class)
    static class TracingConfiguration {

        private final ObjectProvider<OpenTelemetry> openTelemetry;

        TracingConfiguration(ObjectProvider<OpenTelemetry> openTelemetry) {
            this.openTelemetry = openTelemetry;
        }

        @PostConstruct
        public void init() {
            OpenTelemetry otel = openTelemetry.getIfAvailable();
            if (otel != null) {
                TracingFilter.setOpenTelemetry(otel);
            }
        }
    }
}
