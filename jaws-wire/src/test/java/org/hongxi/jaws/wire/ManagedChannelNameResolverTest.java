package org.hongxi.jaws.wire;

import org.hongxi.jaws.exception.JawsServiceException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-style tests for {@link ManagedChannel}'s resolver-driven dynamic
 * backend pool: it opens a {@link WireClient} per resolved address, and on each
 * address update closes the ones that disappeared and opens the ones that are
 * new — so the live set always tracks the resolver.
 * <p>
 * Backends are plain listening {@link ServerSocket}s: {@code WireClient.open()}
 * only needs the TCP connect to succeed (the HTTP/2 preface is written but never
 * answered), so no real gRPC server is required to exercise pool reconciliation.
 *
 * @author shenhongxi
 */
class ManagedChannelNameResolverTest {

    /** A controllable {@link NameResolver} that pushes address updates on demand. */
    private static final class FakeResolver implements NameResolver {
        private volatile NameResolver.Listener listener;
        private volatile boolean shutdown;
        private List<InetSocketAddress> current;

        FakeResolver(List<InetSocketAddress> initial) {
            this.current = initial;
        }

        @Override
        public void start(Listener listener) {
            this.listener = listener;
            listener.onAddresses(current);
        }

        void update(List<InetSocketAddress> addresses) {
            this.current = addresses;
            listener.onAddresses(addresses);
        }

        @Override
        public void shutdown() {
            this.shutdown = true;
        }
    }

    private static List<InetSocketAddress> loopback(int... ports) {
        List<InetSocketAddress> list = new ArrayList<>(ports.length);
        for (int p : ports) {
            list.add(new InetSocketAddress("127.0.0.1", p));
        }
        return list;
    }

    @Test
    void poolTracksResolverAddressUpdates() throws IOException {
        List<ServerSocket> listeners = new ArrayList<>();
        int[] ports = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                ServerSocket ss = new ServerSocket(0);
                listeners.add(ss);
                ports[i] = ss.getLocalPort();
            }

            FakeResolver resolver = new FakeResolver(loopback(ports[0], ports[1], ports[2]));
            ManagedChannel channel = ManagedChannel.builder()
                    .nameResolver(resolver).roundRobin().build();
            assertEquals(3, channel.size(), "initial resolved set opens 3 backends");

            // Capture the client for ports[1] so we can prove it is actually closed
            WireClient dropped = channel.currentClients().stream()
                    .filter(c -> c.getUrl().getPort() == ports[1])
                    .findFirst().orElseThrow();
            assertTrue(dropped.isAvailable(), "captured backend should be live before scale-down");

            // Scale down: drop ports[1]
            resolver.update(loopback(ports[0], ports[2]));
            assertEquals(2, channel.size(), "removed backend is dropped from the pool");
            assertFalse(dropped.isAvailable(),
                    "removed backend must be CLOSED, not just dropped from the pool");

            // Scale up: add ports[3]
            resolver.update(loopback(ports[0], ports[2], ports[3]));
            assertEquals(3, channel.size(), "newly resolved backend is opened");

            channel.close();
            assertEquals(0, channel.size(), "close releases all backends");
            assertTrue(resolver.shutdown, "close shuts down the resolver");
        } finally {
            for (ServerSocket ss : listeners) {
                ss.close();
            }
        }
    }

    @Test
    void buildFailsWhenNoBackendReachable() throws IOException {
        // A port with nothing listening → connect refused → pool ends up empty.
        int deadPort;
        try (ServerSocket ss = new ServerSocket(0)) {
            deadPort = ss.getLocalPort();
        } // closed immediately, so nothing accepts on deadPort

        FakeResolver resolver = new FakeResolver(loopback(deadPort));
        assertThrows(JawsServiceException.class, () -> ManagedChannel.builder()
                .nameResolver(resolver).connectTimeout(500).build());
    }
}
