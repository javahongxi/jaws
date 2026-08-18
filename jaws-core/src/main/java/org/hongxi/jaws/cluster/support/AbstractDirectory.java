package org.hongxi.jaws.cluster.support;

import org.hongxi.jaws.cluster.Directory;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.URL;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Base implementation of {@link Directory} that manages the reference list
 * and change listener notification.
 * <p>
 * Subclasses implement {@link #init()} and {@link #destroy()} to define
 * how references are discovered and released.
 *
 * @param <T> service type
 */
public abstract class AbstractDirectory<T> implements Directory<T> {

    /** The URL used to subscribe and discover services from registry, with nodeType=service. */
    protected final URL consumerUrl;

    private volatile List<Reference<T>> references = List.of();

    private final List<Consumer<List<Reference<T>>>> changeListeners = new CopyOnWriteArrayList<>();

    protected AbstractDirectory(URL consumerUrl) {
        this.consumerUrl = consumerUrl;
    }

    @Override
    public List<Reference<T>> getReferences() {
        return references;
    }

    @Override
    public void addChangeListener(Consumer<List<Reference<T>>> listener) {
        changeListeners.add(listener);
    }

    /**
     * Update the reference list and notify all change listeners.
     *
     * @param newReferences the new reference list
     */
    protected void setReferences(List<Reference<T>> newReferences) {
        this.references = newReferences;
        for (Consumer<List<Reference<T>>> listener : changeListeners) {
            listener.accept(newReferences);
        }
    }

    /**
     * Get the consumer URL used for subscription.
     *
     * @return consumer URL
     */
    public URL getConsumerUrl() {
        return consumerUrl;
    }
}
