package org.hongxi.jaws.store;

/**
 * One decoded record handed from the CommitLog to the reput dispatcher:
 * everything needed to build a ConsumeQueue unit and to notify long polling.
 */
public class DispatchEntry {

    private final long commitLogOffset;
    private final int size;
    private final String topic;
    private final int queueId;
    private final long queueOffset;
    private final long storeTimestamp;

    public DispatchEntry(long commitLogOffset, int size, String topic, int queueId,
                         long queueOffset, long storeTimestamp) {
        this.commitLogOffset = commitLogOffset;
        this.size = size;
        this.topic = topic;
        this.queueId = queueId;
        this.queueOffset = queueOffset;
        this.storeTimestamp = storeTimestamp;
    }

    public long getCommitLogOffset() {
        return commitLogOffset;
    }

    public int getSize() {
        return size;
    }

    public String getTopic() {
        return topic;
    }

    public int getQueueId() {
        return queueId;
    }

    public long getQueueOffset() {
        return queueOffset;
    }

    public long getStoreTimestamp() {
        return storeTimestamp;
    }
}