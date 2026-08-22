package org.hongxi.jaws.filter;

import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.rpc.*;

/**
 * Wrap the protocol to build filter chains for exported providers and referred references.
 * <p>
 * This class acts as a decorator for {@link Protocol}, delegating the actual filter chain
 * construction to {@link FilterChainBuilder}.
 *
 * @see FilterChainBuilder
 * @see FilterProviderWrapper
 * @see FilterReferenceWrapper
 */
public class ProtocolFilterWrapper implements Protocol {

    private final Protocol protocol;
    private final FilterChainBuilder filterChainBuilder;

    public ProtocolFilterWrapper(Protocol protocol) {
        if (protocol == null) {
            throw new JawsFrameworkException("Protocol is null when constructing ProtocolFilterWrapper");
        }
        this.protocol = protocol;
        this.filterChainBuilder = new FilterChainBuilder();
    }

    @Override
    public <T> Exporter<T> export(Provider<T> provider) {
        URL url = provider.getUrl();
        return protocol.export(filterChainBuilder.buildProviderChain(provider, url));
    }

    @Override
    public <T> Reference<T> refer(Class<T> interfaceClass, URL url) {
        return filterChainBuilder.buildReferenceChain(protocol.refer(interfaceClass, url), url);
    }

    @Override
    public void unexport(URL url) {
        protocol.unexport(url);
    }

    @Override
    public void destroy() {
        protocol.destroy();
    }
}
