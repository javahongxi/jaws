package org.hongxi.jaws.wire;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PassthroughNameResolver}: delivers its fixed set once on
 * start, never refreshes, and rejects an empty set.
 *
 * @author shenhongxi
 */
class PassthroughNameResolverTest {

    @Test
    void startDeliversFixedListOnce() {
        List<InetSocketAddress> addrs = List.of(
                InetSocketAddress.createUnresolved("10.0.0.1", 50051),
                InetSocketAddress.createUnresolved("10.0.0.2", 50051));
        PassthroughNameResolver resolver = new PassthroughNameResolver(addrs);

        AtomicReference<List<InetSocketAddress>> got = new AtomicReference<>();
        resolver.start(new NameResolver.Listener() {
            @Override
            public void onAddresses(List<InetSocketAddress> addresses) {
                got.set(addresses);
            }

            @Override
            public void onError(Throwable error) {
                fail("static resolver must not error");
            }
        });

        assertEquals(addrs, got.get());
        assertEquals(2, resolver.getAddresses().size());
    }

    @Test
    void emptyAddressSetRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PassthroughNameResolver(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new PassthroughNameResolver(null));
    }
}
