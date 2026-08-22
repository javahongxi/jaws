package org.hongxi.jaws.rpc;

/**
 * A lifecycle-managed RPC endpoint in the service pipeline.
 *
 * <p>Every endpoint is associated with a {@link URL} describing its identity and configuration,
 * and progresses through init → available → destroy states.
 *
 * <p>Subtypes:
 * <ul>
 *   <li>{@link Exporter} — provider-side endpoint that exposes a service</li>
 *   <li>{@link Caller} → {@link Reference} — consumer-side endpoint that invokes a service</li>
 * </ul>
 */
public interface Endpoint {

    void init();

    void destroy();

    boolean isAvailable();

    String desc();

    URL getUrl();
}
