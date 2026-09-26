package org.hongxi.jaws.transport;

import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.http2.Http2TransportFactory;
import org.hongxi.jaws.transport.netty.NettyTransportFactory;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the transport resolution rules in {@link TransportResolver}: the
 * default fallback, an explicit override, and the protocol/transport
 * compatibility guard.
 */
class TransportResolverTest {

    private static URL url(String protocol, String transportFactory) {
        Map<String, String> params = new HashMap<>();
        if (transportFactory != null) {
            params.put("transportFactory", transportFactory);
        }
        return new URL(protocol, "127.0.0.1", 20880, "org.example.DemoService", params);
    }

    @Test
    void unsetTransportFallsBackToDefault() {
        // The starter side omits the parameter entirely, so this is the shape a
        // jaws service URL actually carries; the UrlParam default must apply
        assertInstanceOf(NettyTransportFactory.class, TransportResolver.resolve(url("jaws", null)),
                "a jaws URL without transportFactory must resolve to the netty default");
    }

    @Test
    void explicitTransportOverridesDefault() {
        assertInstanceOf(Http2TransportFactory.class,
                TransportResolver.resolve(url("jaws", "http2")),
                "an explicit transport must win over the default");
    }

    @Test
    void unknownTransportFailsFast() {
        JawsFrameworkException e = assertThrows(JawsFrameworkException.class,
                () -> TransportResolver.resolve(url("jaws", "no-such-transport")));

        assertTrue(e.getMessage().contains("No transport factory named 'no-such-transport'"),
                "expected the unknown-name guard, got: " + e.getMessage());
    }

    @Test
    void incompatibleProtocolAndTransportFailsFast() {
        // "wire" is served by the jaws-wire transport only; naming netty here is a
        // configuration mistake that must surface at assembly time, not on first call
        JawsFrameworkException e = assertThrows(JawsFrameworkException.class,
                () -> TransportResolver.resolve(url("wire", "netty")));

        assertTrue(e.getMessage().contains("cannot run on transport 'netty'"),
                "expected the compatibility guard, got: " + e.getMessage());
    }
}
