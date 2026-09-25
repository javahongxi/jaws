package org.hongxi.jaws.wire;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DnsNameResolver}: synchronous resolution,
 * periodic refresh, and listener notification.
 *
 * @author shenhongxi
 */
class DnsNameResolverTest {

    @Test
    void resolveNowReturnsLoopback() throws Exception {
        List<InetSocketAddress> addresses = DnsNameResolver.resolveNow("localhost", 8080);
        assertFalse(addresses.isEmpty());
        for (InetSocketAddress addr : addresses) {
            assertEquals(8080, addr.getPort());
        }
    }

    @Test
    void resolveNowThrowsForUnknownHost() {
        assertThrows(UnknownHostException.class,
                () -> DnsNameResolver.resolveNow("nonexistent.invalid.host.xyz", 8080));
    }

    @Test
    void resolverNotifiesListenerOnStart() throws Exception {
        DnsNameResolver resolver = new DnsNameResolver("localhost", 9090, 0);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<InetSocketAddress>> captured = new AtomicReference<>();

        resolver.start(new NameResolver.Listener() {
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

        resolver.shutdown();
    }

    @Test
    void resolverShutdownIsSafe() {
        DnsNameResolver resolver = new DnsNameResolver("localhost", 9090, 100);
        resolver.start(new NameResolver.Listener() {
            @Override
            public void onAddresses(List<InetSocketAddress> addresses) {
            }

            @Override
            public void onError(Throwable error) {
            }
        });

        // shutdown should not throw, and be idempotent
        assertDoesNotThrow(resolver::shutdown);
        assertDoesNotThrow(resolver::shutdown);
    }

    @Test
    void resolverGetHostnameAndPort() {
        DnsNameResolver resolver = new DnsNameResolver("example.com", 443, 5000);
        assertEquals("example.com", resolver.getHostname());
        assertEquals(443, resolver.getDefaultPort());
    }

    @Test
    void throwingAddressListenerDoesNotEscapeTheRefreshTask() {
        // ScheduledExecutorService drops every later run of a fixed-rate task whose
        // body throws, so a transport listener must not be able to take periodic
        // refresh down with it. This asserts the notification seam, not the tick.
        DnsNameResolver resolver = new DnsNameResolver("localhost", 9090, 0);
        AtomicInteger notified = new AtomicInteger();

        assertDoesNotThrow(() -> resolver.start(new NameResolver.Listener() {
            @Override
            public void onAddresses(List<InetSocketAddress> addresses) {
                notified.incrementAndGet();
                throw new IllegalStateException("listener rejected the update");
            }

            @Override
            public void onError(Throwable error) {
            }
        }));

        assertEquals(1, notified.get());
        assertFalse(resolver.getResolvedAddresses().isEmpty(),
                "addresses are recorded before the listener is notified");
        resolver.shutdown();
    }

    @Test
    void throwingErrorListenerDoesNotEscapeTheRefreshTask() {
        // Same guard on the other seam: onError runs from inside the
        // UnknownHostException handler, so it needs covering too.
        DnsNameResolver resolver = new DnsNameResolver("nonexistent.invalid.host.xyz", 9090, 0);
        AtomicInteger errors = new AtomicInteger();

        assertDoesNotThrow(() -> resolver.start(new NameResolver.Listener() {
            @Override
            public void onAddresses(List<InetSocketAddress> addresses) {
            }

            @Override
            public void onError(Throwable error) {
                errors.incrementAndGet();
                throw new IllegalStateException("listener rejected the error");
            }
        }));

        assertEquals(1, errors.get(), "the resolution failure is still reported once");
        resolver.shutdown();
    }
}
