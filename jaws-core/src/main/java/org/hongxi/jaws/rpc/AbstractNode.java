package org.hongxi.jaws.rpc;

import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by shenhongxi on 2021/4/21.
 */
public abstract class AbstractNode implements Node {

    private static final Logger log = LoggerFactory.getLogger(AbstractNode.class);

    protected URL url;

    protected volatile boolean init = false;
    protected volatile boolean available = false;

    public AbstractNode(URL url) {
        this.url = url;
    }

    @Override
    public synchronized void init() {
        if (init) {
            log.warn(this.getClass().getSimpleName() + " node already init: " + desc());
            return;
        }

        boolean result = doInit();

        if (!result) {
            log.error("{} node init Error: {}", this.getClass().getSimpleName(), desc());
            throw new JawsFrameworkException(this.getClass().getSimpleName() + " node init Error: " + desc(),
                    JawsErrorMsgConstants.FRAMEWORK_INIT_ERROR);
        } else {
            log.info("{} node init Success: {}", this.getClass().getSimpleName(), desc());

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