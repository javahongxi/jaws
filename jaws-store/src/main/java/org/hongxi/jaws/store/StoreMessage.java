package org.hongxi.jaws.store;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;

/**
 * A message to append: topic + queueId + body. Also the RPC write payload,
 * so it stays a plain serializable POJO.
 */
public class StoreMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String topic;
    private int queueId;
    private byte[] body;

    public StoreMessage() {
    }

    public StoreMessage(String topic, int queueId, byte[] body) {
        this.topic = topic;
        this.queueId = queueId;
        this.body = body;
    }

    public static StoreMessage of(String topic, int queueId, String text) {
        return new StoreMessage(topic, queueId, text.getBytes(StandardCharsets.UTF_8));
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

    /**
     * Routing identity: topic + queueId, body excluded. ConsistentHashLoadBalance
     * hashes the string form of the arguments, so under loadBalance=consistentHash
     * this pins one queue to one broker — the ordered-send hook in multi-master
     * mode. Keeping the body out avoids hashing megabytes per send.
     */
    @Override
    public String toString() {
        return topic + "@" + queueId;
    }
}