package org.hongxi.jaws.wire;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A registry of {@link NameResolverProvider}s selected by target URI scheme,
 * mirroring grpc-java's {@code NameResolverRegistry}.
 * {@link ManagedChannel.Builder} asks it to turn a {@code target(...)} into a
 * resolver, so new schemes plug in via {@link java.util.ServiceLoader} or a
 * runtime {@link #register}.
 *
 * @author shenhongxi
 */
public final class NameResolverRegistry {
    private static final Logger log = LoggerFactory.getLogger(NameResolverRegistry.class);

    /** Scheme assumed for a bare {@code host:port} target with no scheme. */
    public static final String DEFAULT_SCHEME = "dns";

    private static final NameResolverRegistry INSTANCE = new NameResolverRegistry();

    private final List<NameResolverProvider> providers = new CopyOnWriteArrayList<>();
    private final Map<String, Boolean> seen = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    private NameResolverRegistry() {
        register(new DnsNameResolverProvider());
        register(new PassthroughNameResolverProvider());
    }

    public static NameResolverRegistry getDefault() {
        return INSTANCE;
    }

    /** Add a provider; {@code null}s and duplicates (by name) are ignored. */
    public void register(NameResolverProvider provider) {
        if (provider == null || provider.getName() == null) {
            return;
        }
        if (seen.putIfAbsent(provider.getName(), Boolean.TRUE) == null) {
            providers.add(provider);
        }
    }

    /**
     * @param scheme a URI scheme (case-insensitive)
     * @return the highest-priority provider supporting {@code scheme}, or
     *         {@code null} if none does
     */
    public NameResolverProvider getProvider(String scheme) {
        ensureServiceProvidersLoaded();
        return providers.stream()
                .filter(p -> p.supportsScheme(scheme))
                .max(Comparator.comparingInt(NameResolverProvider::getPriority))
                .orElse(null);
    }

    /**
     * Build a resolver from a target, choosing the provider by the target's scheme.
     *
     * @param target {@code scheme:///authority}, {@code scheme://authority}, or a
     *               bare {@code host:port} (uses {@link #DEFAULT_SCHEME})
     * @param args   construction context
     * @throws IllegalArgumentException if no provider handles the target's scheme
     */
    public NameResolver newNameResolver(String target, NameResolver.Args args) {
        String scheme = schemeOf(target);
        String authority = authorityOf(target);
        NameResolverProvider provider = getProvider(scheme);
        if (provider == null) {
            throw new IllegalArgumentException(
                    "No NameResolverProvider for scheme '" + scheme + "' in target '"
                            + target + "'");
        }
        return provider.newNameResolver(authority, args);
    }

    /** Extract the scheme, or {@link #DEFAULT_SCHEME} when the target is bare. */
    static String schemeOf(String target) {
        int idx = target.indexOf("://");
        return idx >= 0 ? target.substring(0, idx) : DEFAULT_SCHEME;
    }

    /** Strip the scheme (and any leading slashes of an empty authority). */
    static String authorityOf(String target) {
        int idx = target.indexOf("://");
        String rest = idx >= 0 ? target.substring(idx + 3) : target;
        int s = 0;
        while (s < rest.length() && rest.charAt(s) == '/') {
            s++;
        }
        return rest.substring(s);
    }

    /**
     * Parse a {@code host:port} authority into an unresolved
     * {@link InetSocketAddress}, shared by the built-in providers.
     */
    static java.net.InetSocketAddress parseHostPort(String hostPort) {
        int idx = hostPort.lastIndexOf(':');
        if (idx < 0) {
            throw new IllegalArgumentException("target authority must be host:port but was: "
                    + hostPort);
        }
        String host = hostPort.substring(0, idx).trim();
        int port = Integer.parseInt(hostPort.substring(idx + 1).trim());
        return java.net.InetSocketAddress.createUnresolved(host, port);
    }

    private void ensureServiceProvidersLoaded() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            try {
                for (NameResolverProvider provider :
                        ServiceLoader.load(NameResolverProvider.class)) {
                    register(provider);
                }
            } catch (Throwable t) {
                log.warn("Failed to load ServiceLoader NameResolverProviders: {}",
                        t.getMessage());
            }
            loaded = true;
        }
    }
}
