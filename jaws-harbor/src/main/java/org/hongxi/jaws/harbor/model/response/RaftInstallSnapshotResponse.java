package org.hongxi.jaws.harbor.model.response;

import org.hongxi.jaws.harbor.model.Response;

/**
 * Reply to {@code RaftInstallSnapshotRequest}. Only the term travels back: the
 * inherited {@code success} flag tells the leader whether the snapshot was
 * installed, and a newer term makes it step down.
 *
 * @author shenhongxi
 */
public class RaftInstallSnapshotResponse extends Response {

    private long term;

    public long getTerm() {
        return term;
    }

    public void setTerm(long term) {
        this.term = term;
    }
}