package org.hongxi.jaws.lifecycle;

/**
 * Lifecycle contract for components that hold resources (connections,
 * threads, registries) and must release them on shutdown.
 * <p>
 * Implemented by protocol endpoints, clusters, and references so the
 * framework can deterministically close consumer and provider resources.
 *
 * <p>
 * Created by shenhongxi on 2021/4/23.
 */
public interface Closeable {

    void close();
}
