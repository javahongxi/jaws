package org.hongxi.jaws.wire;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Builds a {@link PassthroughNameResolver} serving a single literal backend for
 * {@code passthrough:///host:port} targets — the "no discovery, just this address"
 * case, analogous to grpc-java's pass-through resolver.
 *
 * @author shenhongxi
 */
public final class PassthroughNameResolverProvider implements NameResolverProvider {

    @Override
    public String getName() {
        return "passthrough";
    }

    @Override
    public boolean supportsScheme(String scheme) {
        return "passthrough".equalsIgnoreCase(scheme);
    }

    @Override
    public NameResolver newNameResolver(String targetUri, NameResolver.Args args) {
        InetSocketAddress addr = NameResolverRegistry.parseHostPort(targetUri);
        return new PassthroughNameResolver(List.of(addr));
    }
}
