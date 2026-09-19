package org.hongxi.jaws.wire;

/**
 * SPI descriptor for a {@link NameResolver}: which target URI scheme it builds a
 * resolver for, and how to construct one. Mirrors grpc-java's
 * {@code io.grpc.NameResolverProvider}.
 * <p>
 * Discovered via {@link java.util.ServiceLoader} (register an implementation in
 * {@code META-INF/services/org.hongxi.jaws.wire.NameResolverProvider}) or added at
 * runtime through {@link NameResolverRegistry#register}. This lets a
 * {@link ManagedChannel} turn {@code target("scheme:///authority")} into a resolver
 * without the channel knowing every scheme up front.
 *
 * @author shenhongxi
 */
public interface NameResolverProvider {

    /**
     * @return a stable identifier for diagnostics (typically the scheme name)
     */
    String getName();

    /**
     * Higher priority wins when two providers {@link #supportsScheme(String) claim}
     * the same scheme. Built-ins use {@code 5}.
     */
    default int getPriority() {
        return 5;
    }

    /**
     * @param scheme the URI scheme of a target, e.g. {@code "dns"} or
     *               {@code "passthrough"}
     * @return true if this provider can build a resolver for that scheme
     */
    boolean supportsScheme(String scheme);

    /**
     * Build a resolver for the given target.
     *
     * @param targetUri the target authority with the scheme stripped (e.g.
     *                  {@code host:port}); never null
     * @param args      construction context (e.g. DNS refresh interval)
     * @return a resolver that has not yet been {@link NameResolver#start started}
     */
    NameResolver newNameResolver(String targetUri, NameResolver.Args args);
}
