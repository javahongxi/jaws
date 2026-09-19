package org.hongxi.jaws.wire;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice 2 of P2: the pluggable {@link NameResolverProvider}/{@link
 * NameResolverRegistry} seam. Covers scheme parsing, built-in dispatch, unknown-
 * scheme rejection, runtime registration of a custom provider, and that
 * {@link ManagedChannel.Builder} routes {@code target(...)} through the registry.
 *
 * @author shenhongxi
 */
class NameResolverRegistryTest {

    private static final NameResolver.Args ARGS = new NameResolver.Args(30_000L);

    @Test
    void parsesSchemeAndAuthority() {
        assertEquals("dns", NameResolverRegistry.schemeOf("dns:///host:1"));
        assertEquals("passthrough", NameResolverRegistry.schemeOf("passthrough:///host:1"));
        assertEquals("dns", NameResolverRegistry.schemeOf("host:1"), "bare → default scheme");

        assertEquals("host:1", NameResolverRegistry.authorityOf("dns:///host:1"));
        assertEquals("host:1", NameResolverRegistry.authorityOf("dns://host:1"));
        assertEquals("host:1", NameResolverRegistry.authorityOf("host:1"));
    }

    @Test
    void builtinProvidersResolveTheirSchemes() {
        NameResolverRegistry reg = NameResolverRegistry.getDefault();
        assertInstanceOf(DnsNameResolver.class,
                reg.newNameResolver("dns:///localhost:50051", ARGS));
        assertInstanceOf(PassthroughNameResolver.class,
                reg.newNameResolver("passthrough:///127.0.0.1:50051", ARGS));
        // Bare host:port falls back to the default (dns) provider.
        assertInstanceOf(DnsNameResolver.class,
                reg.newNameResolver("localhost:50051", ARGS));
    }

    @Test
    void unknownSchemeRejected() {
        assertNull(NameResolverRegistry.getDefault().getProvider("nosuchscheme"));
        assertThrows(IllegalArgumentException.class,
                () -> NameResolverRegistry.getDefault()
                        .newNameResolver("nosuchscheme:///host:1", ARGS));
    }

    @Test
    void runtimeRegisteredProviderIsUsed() {
        NameResolverRegistry.getDefault().register(new TestNameResolverProvider());
        NameResolver resolver = NameResolverRegistry.getDefault()
                .newNameResolver("test:///whatever:1", ARGS);
        assertInstanceOf(TestNameResolver.class, resolver);
    }

    @Test
    void builderRoutesTargetThroughRegistry() throws IOException {
        try (ServerSocket ss = new ServerSocket(0);
             ManagedChannel ch = ManagedChannel.builder()
                     .target("passthrough:///127.0.0.1:" + ss.getLocalPort())
                     .build()) {
            assertEquals(1, ch.size(), "passthrough target resolved to one live backend");
            assertInstanceOf(PassthroughNameResolver.class,
                    NameResolverRegistry.getDefault()
                            .newNameResolver("passthrough:///x:1", ARGS));
        }
    }

    // ---- a minimal custom resolver/provider to prove the SPI seam ----

    private static final class TestNameResolverProvider implements NameResolverProvider {
        @Override
        public String getName() {
            return "test";
        }

        @Override
        public boolean supportsScheme(String scheme) {
            return "test".equalsIgnoreCase(scheme);
        }

        @Override
        public NameResolver newNameResolver(String targetUri, NameResolver.Args args) {
            return new TestNameResolver();
        }
    }

    private static final class TestNameResolver implements NameResolver {
        @Override
        public void start(Listener listener) {
            listener.onAddresses(List.of(InetSocketAddress.createUnresolved("127.0.0.1", 1)));
        }

        @Override
        public void shutdown() {
        }
    }
}
