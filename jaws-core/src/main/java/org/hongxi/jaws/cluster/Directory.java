package org.hongxi.jaws.cluster;

import org.hongxi.jaws.rpc.Reference;

import java.util.List;
import java.util.function.Consumer;

/**
 * Directory represents a dynamic or static list of service references.
 * <p>
 * It abstracts the source of references: a registry (dynamic) or a fixed list (static).
 * When the reference list changes, registered listeners are notified.
 *
 * @param <T> service type
 */
public interface Directory<T> {

    /**
     * Get the current list of available references.
     *
     * @return current reference list
     */
    List<Reference<T>> getReferences();

    /**
     * Initialize the directory (e.g. subscribe to registry).
     */
    void init();

    /**
     * Register a listener to be notified when references change.
     *
     * @param listener callback receiving the updated reference list
     */
    void addChangeListener(Consumer<List<Reference<T>>> listener);

    /**
     * Destroy the directory and release resources.
     */
    void destroy();
}
