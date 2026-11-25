package org.hongxi.jaws.store;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Pull result: matched messages plus the offset bookkeeping the consumer
 * needs to continue (nextBeginOffset) or to detect reset (min/max).
 */
public class PullResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private PullStatus status;
    private long nextBeginOffset;
    private long minOffset;
    private long maxOffset;
    private List<MessageView> messages = new ArrayList<>();

    public PullResult() {
    }

    public PullResult(PullStatus status, long nextBeginOffset, long minOffset, long maxOffset) {
        this.status = status;
        this.nextBeginOffset = nextBeginOffset;
        this.minOffset = minOffset;
        this.maxOffset = maxOffset;
    }

    public PullStatus getStatus() {
        return status;
    }

    public void setStatus(PullStatus status) {
        this.status = status;
    }

    public long getNextBeginOffset() {
        return nextBeginOffset;
    }

    public void setNextBeginOffset(long nextBeginOffset) {
        this.nextBeginOffset = nextBeginOffset;
    }

    public long getMinOffset() {
        return minOffset;
    }

    public void setMinOffset(long minOffset) {
        this.minOffset = minOffset;
    }

    public long getMaxOffset() {
        return maxOffset;
    }

    public void setMaxOffset(long maxOffset) {
        this.maxOffset = maxOffset;
    }

    public List<MessageView> getMessages() {
        return messages;
    }

    public void setMessages(List<MessageView> messages) {
        this.messages = messages;
    }
}