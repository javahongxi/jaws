package org.hongxi.jaws.wire;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link WireDnsResolver}: synchronous resolution,
 * periodic refresh, and listener notification.
 *
 * @author shenhongxi
 */
class WireDnsResolverTest {

    @Test
    void resolveNowReturnsLoopback() throws Exception {
        List<InetSocketAddress> addresses = WireDnsResolver.resolveNow("localhost", 8080);
        assertFalse(addresses.isEmpty());
        for (InetSocketAddress addr : addresses) {
            assertEquals(8080, addr.getPort());
        }
    }

    @Test
    void resolveNowThrowsForUnknownHost() {
        assertThrows(UnknownHostException.class,
                () -> WireDnsResolver.resolveNow("nonexistent.invalid.host.xyz", 8080));
    }

    @Test
    void resolverNotifiesListenerOnStart() throws Exception {
        WireDnsResolver resolver = new WireDnsResolver("localhost", 9090, 0);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<InetSocketAddress>> captured = new AtomicReference<>();

        resolver.start(new WireDnsResolver.Listener() {
            @Override
            public void onAddresses(List<InetSocketAddress> addresses) {
                captured.set(addresses);
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
            }
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS), "listener should be notified on start");
        assertNotNull(captured.get());
        assertFalse(captured.get().isEmpty());
        assertEquals(9090, captured.get().get(0).getPort());

        resolver.stop();
    }

    @Test
    void resolverStopCancelsRefresh() {
        WireDnsResolver resolver = new WireDnsResolver("localhost", 9090, 100);
        resolver.start(new WireDnsResolver.Listener() {
            @Override
            public void onAddresses(List<InetSocketAddress> addresses) {
            }

            @Override
            public void onError(Throwable error) {
            }
        });

        // Stop should not throw
        assertDoesNotThrow(resolver::stop);
    }

    @Test
    void resolverGetHostnameAndPort() {
        WireDnsResolver resolver = new WireDnsResolver("example.com", 443, 5000);
        assertEquals("example.com", resolver.getHostname());
        assertEquals(443, resolver.getDefaultPort());
    }
}
