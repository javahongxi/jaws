package org.hongxi.jaws.wire;

import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice 1 of P2: the pluggable {@link LoadBalancer}/{@link SubchannelPicker}
 * seam. Covers the two built-ins' pick ordering, {@link LoadBalancerRegistry}
 * name resolution, and that {@link ManagedChannel} honours a caller-supplied
 * load balancer. {@link WireClient}s here are constructed but never opened —
 * {@code pick()} only reasons about identity and order, not connectivity.
 *
 * @author shenhongxi
 */
class LoadBalancerTest {

    private static WireClient fakeClient(int port) {
        // Unopened: only used as an identity token for ordering assertions.
        return new WireClient(new URL("wire", "127.0.0.1", port, "wire"));
    }

    @Test
    void roundRobinRotatesTheStartIndexPerPick() {
        WireClient a = fakeClient(1), b = fakeClient(2), c = fakeClient(3);
        RoundRobinLoadBalancer lb = new RoundRobinLoadBalancer();
        lb.resolvedAddresses(List.of(a, b, c));

        List<WireClient> first = lb.picker().pick();
        List<WireClient> second = lb.picker().pick();
        List<WireClient> third = lb.picker().pick();
        List<WireClient> fourth = lb.picker().pick();

        assertEquals(a, first.get(0), "first pick starts at index 0");
        assertEquals(b, second.get(0), "second pick advances to index 1");
        assertEquals(c, third.get(0), "third pick advances to index 2");
        assertEquals(a, fourth.get(0), "fourth pick wraps back to index 0");
        // Each pick still returns the full pool (failover order), just rotated.
        assertEquals(3, first.size());
        assertEquals(List.of(b, c, a), second);
    }

    @Test
    void pickFirstKeepsResolutionOrder() {
        WireClient a = fakeClient(1), b = fakeClient(2), c = fakeClient(3);
        PickFirstLoadBalancer lb = new PickFirstLoadBalancer();
        lb.resolvedAddresses(List.of(a, b, c));

        assertEquals(List.of(a, b, c), lb.picker().pick());
        assertEquals(List.of(a, b, c), lb.picker().pick(), "stable across picks");
    }

    @Test
    void emptyPoolPicksNothing() {
        assertTrue(new RoundRobinLoadBalancer().picker().pick().isEmpty());
        assertTrue(new PickFirstLoadBalancer().picker().pick().isEmpty());
    }

    @Test
    void registryResolvesBuiltinNames() {
        LoadBalancerRegistry reg = LoadBalancerRegistry.getDefault();
        assertInstanceOf(RoundRobinLoadBalancer.class, reg.newLoadBalancer("round_robin"));
        assertInstanceOf(PickFirstLoadBalancer.class, reg.newLoadBalancer("pick_first"));
        assertNotNull(reg.getProvider("round_robin"));
    }

    @Test
    void registryRejectsUnknownName() {
        assertThrows(IllegalArgumentException.class,
                () -> LoadBalancerRegistry.getDefault().newLoadBalancer("no-such-policy"));
    }

    @Test
    void builderHonoursCustomLoadBalancerInstance() throws IOException {
        CapturingLoadBalancer custom = new CapturingLoadBalancer();
        try (ServerSocket ss = new ServerSocket(0)) {
            try (ManagedChannel ch = ManagedChannel.builder()
                    .addAddress("127.0.0.1:" + ss.getLocalPort())
                    .loadBalancer(custom)
                    .build()) {
                assertEquals(1, custom.resolvedSizes.get(custom.resolvedSizes.size() - 1),
                        "channel pushed the live pool into the custom LB");
                assertEquals(1, ch.size());
            }
            assertTrue(custom.shutdownCalled, "channel shut the custom LB down on close");
        }
    }

    /** Records what the channel feeds it and always prefers the last backend. */
    private static final class CapturingLoadBalancer implements LoadBalancer {
        final List<Integer> resolvedSizes = new ArrayList<>();
        volatile boolean shutdownCalled;

        @Override
        public void resolvedAddresses(List<WireClient> backends) {
            resolvedSizes.add(backends.size());
        }

        @Override
        public SubchannelPicker picker() {
            return List::of;   // never actually used for a call in this test
        }

        @Override
        public void shutdown() {
            shutdownCalled = true;
        }
    }
}
