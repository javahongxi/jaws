package org.hongxi.jaws.harbor.model.response;

import org.hongxi.jaws.harbor.model.Response;

/**
 * Reply to {@code RaftAppendRequest}. The inherited {@code success} flag tells
 * whether the prev-log check passed; {@code lastLogIndex} reports the follower's
 * log tail so a rejected leader can back {@code nextIndex} up in one jump instead
 * of one entry per round trip.
 *
 * @author shenhongxi
 */
public class RaftAppendResponse extends Response {

    private long term;
    private long lastLogIndex;

    public long getTerm() {
        return term;
    }

    public void setTerm(long term) {
        this.term = term;
    }

    public long getLastLogIndex() {
        return lastLogIndex;
    }

    public void setLastLogIndex(long lastLogIndex) {
        this.lastLogIndex = lastLogIndex;
    }
}