package org.hongxi.jaws.harbor.client;

import org.hongxi.jaws.harbor.model.Instance;

import java.util.List;

/**
 * Redo entry of a batch registration. Named and shaped after Nacos's
 * {@code BatchInstanceRedoData}: it extends the single-instance entry rather than
 * sitting in a table of its own, because a service is owed at most one kind of
 * registration — and a replay has to know which shape to send to put all of them
 * back.
 *
 * @author shenhongxi
 */
class BatchInstanceRedoData extends InstanceRedoData {

    private final List<Instance> instances;

    BatchInstanceRedoData(List<Instance> instances) {
        super(instances.isEmpty() ? null : instances.get(0));
        this.instances = List.copyOf(instances);
    }

    @Override
    List<Instance> instances() {
        return instances;
    }
}
