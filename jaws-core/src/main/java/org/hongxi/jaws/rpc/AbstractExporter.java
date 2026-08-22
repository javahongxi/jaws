package org.hongxi.jaws.rpc;

/**
 * Base implementation of {@link Exporter} holding the exported {@link Provider}
 * together with its {@link URL}, and wiring endpoint lifecycle via
 * {@link AbstractEndpoint}.
 * <p>
 * Subclasses (one per protocol) implement the actual service exposure and
 * {@link #destroy()} behavior.
 *
 * <p>Created by shenhongxi on 2021/4/21.
 */
public abstract class AbstractExporter<T> extends AbstractEndpoint implements Exporter<T> {
    protected Provider<T> provider;

    public AbstractExporter(Provider<T> provider, URL url) {
        super(url);
        this.provider = provider;
    }

    public Provider<T> getProvider() {
        return provider;
    }

    @Override
    public String desc() {
        return "[" + this.getClass().getSimpleName() + "] url=" + url;
    }
}