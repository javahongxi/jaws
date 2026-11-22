package org.hongxi.jaws.metadata;

import org.hongxi.jaws.configcenter.DynamicConfigurationUtils;
import org.hongxi.jaws.configcenter.LocalDynamicConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapping read/write contract of {@link ServiceNameMapping}: an absent or blank
 * mapping must read as null so the consumer falls back to interface-level discovery.
 */
class ServiceNameMappingTest {

    private static final String IFACE = "com.example.DemoService";

    @BeforeEach
    void setUp() {
        DynamicConfigurationUtils.setDynamicConfiguration(new LocalDynamicConfiguration());
    }

    @Test
    void setThenGetRoundtrip() {
        ServiceNameMapping.setMapping(IFACE, "sample-app-provider");
        assertEquals("sample-app-provider", ServiceNameMapping.getMapping(IFACE));
    }

    @Test
    void absentMappingReadsNull() {
        assertNull(ServiceNameMapping.getMapping(IFACE));
    }

    @Test
    void blankMappingReadsNull() {
        DynamicConfigurationUtils.setConfig(ServiceNameMapping.mappingKey(IFACE), "  ");
        assertNull(ServiceNameMapping.getMapping(IFACE));
    }

    @Test
    void mappingKeyUsesDedicatedPrefix() {
        assertEquals("mapping:" + IFACE, ServiceNameMapping.mappingKey(IFACE));
    }

    @Test
    void lastWriterWins() {
        ServiceNameMapping.setMapping(IFACE, "app-a");
        ServiceNameMapping.setMapping(IFACE, "app-b");
        assertEquals("app-b", ServiceNameMapping.getMapping(IFACE));
    }

    @Test
    void removeRestoresInterfaceLevelFallback() {
        ServiceNameMapping.setMapping(IFACE, "sample-app-provider");
        assertTrue(ServiceNameMapping.removeMapping(IFACE));
        assertNull(ServiceNameMapping.getMapping(IFACE));
    }

    @Test
    void removingAbsentMappingReportsNothingRemoved() {
        assertFalse(ServiceNameMapping.removeMapping(IFACE));
    }
}