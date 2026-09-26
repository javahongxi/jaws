package org.hongxi.jaws.spring.boot;

import org.hongxi.jaws.config.ProtocolConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins that the starter no longer injects an implicit {@code netty} transport:
 * an unset {@code jaws.protocol.transport-factory} must leave the parameter out
 * of the URL so {@code TransportResolver} can derive it from the protocol name.
 */
class JawsAutoConfigurationTransportFactoryTest {

    private static ProtocolConfig protocolConfigOf(String transportFactory) {
        JawsProperties properties = new JawsProperties();
        properties.getProtocol().setName("wire");
        properties.getProtocol().setTransportFactory(transportFactory);
        return new JawsAutoConfiguration(properties).protocolConfig();
    }

    @Test
    void propertyHasNoImplicitTransportDefault() {
        assertNull(new JawsProperties().getProtocol().getTransportFactory(),
                "transport-factory must stay unset by default; a hardcoded netty "
                        + "would override the protocol-name fallback");
    }

    @Test
    void unsetTransportIsNotPassedToProtocolConfig() {
        assertNull(protocolConfigOf(null).getTransportFactory(),
                "an unset transport must not be materialised as a value");
    }

    @Test
    void blankTransportIsNotPassedToProtocolConfig() {
        assertNull(protocolConfigOf("   ").getTransportFactory(),
                "a blank transport is treated as unset, not forwarded");
    }

    @Test
    void explicitTransportIsPassedThrough() {
        assertEquals("http2", protocolConfigOf("http2").getTransportFactory(),
                "an explicit choice must still reach TransportResolver");
    }
}
