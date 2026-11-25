package org.hongxi.jaws.store;

import java.io.Serializable;

/**
 * Append result returned to producers, both in-JVM and over RPC.
 */
public class PutResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private PutStatus status;
    private long commitLogOffset;
    private long queueOffset;

    public PutResult() {
    }

    public PutResult(PutStatus status) {
        this.status = status;
    }

    public PutResult(PutStatus status, long commitLogOffset, long queueOffset) {
        this.status = status;
        this.commitLogOffset = commitLogOffset;
        this.queueOffset = queueOffset;
    }

    public boolean isOk() {
        return status == PutStatus.PUT_OK;
    }

    public PutStatus getStatus() {
        return status;
    }

    public void setStatus(PutStatus status) {
        this.status = status;
    }

    public long getCommitLogOffset() {
        return commitLogOffset;
    }

    public void setCommitLogOffset(long commitLogOffset) {
        this.commitLogOffset = commitLogOffset;
    }

    public long getQueueOffset() {
        return queueOffset;
    }

    public void setQueueOffset(long queueOffset) {
        this.queueOffset = queueOffset;
    }
}