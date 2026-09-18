package org.hongxi.jaws.harbor.client;

import org.hongxi.jaws.harbor.model.Instance;

import java.util.List;

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

    /**
     * The instances this entry owes, in whichever shape it was registered: replay
     * and batch-deregistration both have to reason about the whole set, not about
     * the last instance written.
     */
    List<Instance> instances() {
        return instance == null ? List.of() : List.of(instance);
    }
}
