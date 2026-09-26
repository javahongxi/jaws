package org.hongxi.jaws.wire;

import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.TransportFactory;
import org.hongxi.jaws.transport.TransportResolver;
import org.hongxi.jaws.transport.netty.NettyTransportFactory;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Pins the transport-name fallback that lets a wire endpoint omit the
 * {@code transport-factory} parameter entirely: the protocol name selects the
 * transport registered under it.
 */
class WireTransportResolutionTest {

    private static URL url(String protocol) {
        Map<String, String> params = new HashMap<>();
        return new URL(protocol, "127.0.0.1", 50051, "org.example.GreeterService", params);
    }

    @Test
    void wireProtocolWithoutTransportNameSelectsWireTransport() {
        TransportFactory factory = TransportResolver.resolve(url("wire"));

        assertInstanceOf(WireTransportFactory.class, factory,
                "name=wire with no transport-factory must land on the wire transport");
    }

    @Test
    void otherProtocolStillFallsBackToNetty() {
        // The fallback is protocol-name based, not a blanket "wire always wins":
        // a protocol without a same-named transport keeps the UrlParam default
        assertInstanceOf(NettyTransportFactory.class, TransportResolver.resolve(url("jaws")));
    }

    @Test
    void wireTransportAcceptsOnlyTheWireProtocol() {
        assertEquals(Set.of("wire"), new WireTransportFactory().supportedProtocols(),
                "gRPC framing is bound to the wire protocol and must not claim jaws");
    }
}
