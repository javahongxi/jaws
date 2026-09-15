package org.hongxi.jaws.wire;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * A {@link NameResolver} over a fixed, caller-supplied set of literal
 * {@code host:port} endpoints. Delivers the list once on {@link #start} and
 * never changes — the equivalent of grpc-java's {@code passthrough:///} scheme.
 * This is what {@link ManagedChannel.Builder#addAddress} uses under the hood.
 *
 * @author shenhongxi
 */
public final class StaticNameResolver implements NameResolver {

    private final List<InetSocketAddress> addresses;

    public StaticNameResolver(List<InetSocketAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            throw new IllegalArgumentException("StaticNameResolver requires at least one address");
        }
        this.addresses = List.copyOf(addresses);
    }

    @Override
    public void start(Listener listener) {
        listener.onAddresses(addresses);
    }

    @Override
    public void shutdown() {
        // nothing to release
    }

    /**
     * @return the fixed endpoint set this resolver serves
     */
    public List<InetSocketAddress> getAddresses() {
        return addresses;
    }
}
