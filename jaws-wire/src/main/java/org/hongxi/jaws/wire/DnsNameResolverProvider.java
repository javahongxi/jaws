package org.hongxi.jaws.wire;

import java.net.InetSocketAddress;

/**
 * Builds {@link DnsNameResolver}s for {@code dns:///host:port} targets.
 *
 * @author shenhongxi
 */
public final class DnsNameResolverProvider implements NameResolverProvider {

    @Override
    public String getName() {
        return "dns";
    }

    @Override
    public boolean supportsScheme(String scheme) {
        return "dns".equalsIgnoreCase(scheme);
    }

    @Override
    public NameResolver newNameResolver(String targetUri, NameResolver.Args args) {
        InetSocketAddress addr = NameResolverRegistry.parseHostPort(targetUri);
        return new DnsNameResolver(
                addr.getHostString(), addr.getPort(), args.dnsRefreshIntervalMs());
    }
}
