package org.hongxi.jaws.harbor.model.request;

import org.hongxi.jaws.harbor.model.RaftEntry;
import org.hongxi.jaws.harbor.model.Request;

import java.util.List;

/**
 * Raft AppendEntries RPC (paper §5.3), used for both log replication and
 * heartbeats (empty {@code entries}). The follower accepts only when its log
 * matches {@code prevLogIndex}/{@code prevLogTerm}; {@code leaderCommit} tells
 * it how far the committed prefix reaches.
 *
 * @author shenhongxi
 */
public class RaftAppendRequest extends Request {

    private long term;
    private String leaderId;
    private long prevLogIndex;
    private long prevLogTerm;
    private long leaderCommit;
    private List<RaftEntry> entries;

    public long getTerm() {
        return term;
    }

    public void setTerm(long term) {
        this.term = term;
    }

    public String getLeaderId() {
        return leaderId;
    }

    public void setLeaderId(String leaderId) {
        this.leaderId = leaderId;
    }

    public long getPrevLogIndex() {
        return prevLogIndex;
    }

    public void setPrevLogIndex(long prevLogIndex) {
        this.prevLogIndex = prevLogIndex;
    }

    public long getPrevLogTerm() {
        return prevLogTerm;
    }

    public void setPrevLogTerm(long prevLogTerm) {
        this.prevLogTerm = prevLogTerm;
    }

    public long getLeaderCommit() {
        return leaderCommit;
    }

    public void setLeaderCommit(long leaderCommit) {
        this.leaderCommit = leaderCommit;
    }

    public List<RaftEntry> getEntries() {
        return entries;
    }

    public void setEntries(List<RaftEntry> entries) {
        this.entries = entries;
    }
}