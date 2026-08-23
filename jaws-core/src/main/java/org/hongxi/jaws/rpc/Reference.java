package org.hongxi.jaws.rpc;

import org.hongxi.jaws.common.extension.Spi;

/**
 * Client-side invocation abstraction representing a reference to a remote service
 * (roughly the consumer-side {@code Invoker} in Dubbo). In addition to issuing calls
 * via {@link Caller#call(Request)}, it reports {@link #activeReferenceCount()} so that
 * load-balancing strategies can estimate in-flight load per endpoint, and exposes the
 * service URL through {@link #getServiceUrl()}. Registered as a prototype-scoped SPI.
 *
 * <p>Created by shenhongxi on 2021/4/21.
 *
 * @see AbstractReference
 */
@Spi
public interface Reference<T> extends Caller<T>, Endpoint {

    /**
     * The number of active calls currently using this reference.
     *
     * @return active call count
     */
    int activeReferenceCount();

    /**
     * Get the original service URL of this reference.
     *
     * @return service URL
     */
    URL getServiceUrl();
}