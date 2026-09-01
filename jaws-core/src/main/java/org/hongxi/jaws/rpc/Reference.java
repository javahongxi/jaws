package org.hongxi.jaws.rpc;

/**
 * Client-side invocation abstraction representing a reference to a remote service
 * (roughly the consumer-side {@code Invoker} in Dubbo). In addition to issuing calls
 * via {@link Caller#call(Request)}, it reports {@link #activeCallCount()} so that
 * load-balancing strategies can estimate in-flight load per endpoint, and exposes the
 * service URL through {@link #getServiceUrl()}. Instances are stateful and bound to
 * one service URL, so they are created per URL by the protocol rather than loaded
 * as an SPI extension.
 *
 * <p>Created by shenhongxi on 2021/4/21.
 *
 * @see AbstractReference
 */
public interface Reference<T> extends Caller<T>, Endpoint {

    /**
     * The number of active calls currently using this reference.
     *
     * @return active call count
     */
    int activeCallCount();

    /**
     * Get the original service URL of this reference.
     *
     * @return service URL
     */
    URL getServiceUrl();
}