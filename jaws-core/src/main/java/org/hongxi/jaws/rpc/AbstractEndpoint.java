package org.hongxi.jaws.rpc;

import org.hongxi.jaws.exception.JawsFrameworkException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base implementation of {@link Endpoint} providing a template-method lifecycle.
 *
 * <p>Subclasses implement {@link #doInit()} to perform endpoint-specific initialization.
 * The {@link #init()} method guards against double initialization and transitions
 * the endpoint to available state upon success.
 */
public abstract class AbstractEndpoint implements Endpoint {

    private static final Logger log = LoggerFactory.getLogger(AbstractEndpoint.class);

    protected URL url;

    protected volatile boolean init = false;
    protected volatile boolean available = false;

    public AbstractEndpoint(URL url) {
        this.url = url;
    }

    @Override
    public synchronized void init() {
        if (init) {
            log.warn("{} endpoint already init: {}", this.getClass().getSimpleName(), desc());
            return;
        }

        boolean result = doInit();

        if (!result) {
            log.error("{} endpoint init Error: {}", this.getClass().getSimpleName(), desc());
            throw new JawsFrameworkException(this.getClass().getSimpleName() + " endpoint init Error: " + desc());
        } else {
            log.info("{} endpoint init Success: {}", this.getClass().getSimpleName(), desc());

            init = true;
            available = true;
        }
    }

    protected abstract boolean doInit();

    @Override
    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public URL getUrl() {
        return url;
    }
}
