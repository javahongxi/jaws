package org.hongxi.jaws.harbor.client;

import org.hongxi.jaws.harbor.model.Instance;

/**
 * Redo entry of one registered instance. Named after Nacos's
 * {@code InstanceRedoData}; the payload is replaceable because re-registering
 * the same service with a new instance (a different port after a restart, for
 * example) must replay the newest value, not the one first cached.
 *
 * @author shenhongxi
 */
class InstanceRedoData extends RedoData {

    private volatile Instance instance;

    InstanceRedoData(Instance instance) {
        this.instance = instance;
    }

    Instance instance() {
        return instance;
    }

    void setInstance(Instance instance) {
        this.instance = instance;
    }
}
