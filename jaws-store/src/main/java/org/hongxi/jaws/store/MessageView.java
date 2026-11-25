package org.hongxi.jaws.store;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;

/**
 * A message read back from the store, carrying both the logical position
 * (queueOffset) and the physical position (commitLogOffset).
 */
public class MessageView implements Serializable {

    private static final long serialVersionUID = 1L;

    private String topic;
    private int queueId;
    private byte[] body;
    private long queueOffset;
    private long commitLogOffset;
    private long storeTimestamp;
    private int storeSize;

    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public int getQueueId() {
        return queueId;
    }

    public void setQueueId(int queueId) {
        this.queueId = queueId;
    }

    public byte[] getBody() {
        return body;
    }

    public void setBody(byte[] body) {
        this.body = body;
    }

    public long getQueueOffset() {
        return queueOffset;
    }

    public void setQueueOffset(long queueOffset) {
        this.queueOffset = queueOffset;
    }

    public long getCommitLogOffset() {
        return commitLogOffset;
    }

    public void setCommitLogOffset(long commitLogOffset) {
        this.commitLogOffset = commitLogOffset;
    }

    public long getStoreTimestamp() {
        return storeTimestamp;
    }

    public void setStoreTimestamp(long storeTimestamp) {
        this.storeTimestamp = storeTimestamp;
    }

    public int getStoreSize() {
        return storeSize;
    }

    public void setStoreSize(int storeSize) {
        this.storeSize = storeSize;
    }
}