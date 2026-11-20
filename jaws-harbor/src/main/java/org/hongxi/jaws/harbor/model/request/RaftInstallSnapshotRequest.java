package org.hongxi.jaws.harbor.model.request;

import org.hongxi.jaws.harbor.model.Request;

/**
 * Raft InstallSnapshot RPC (paper §7): the leader sends the whole compacted
 * state when a follower's needed entries have already been truncated into a
 * snapshot. {@code data} is the Base64 of the state-machine snapshot taken at
 * {@code lastIncludedIndex}/{@code lastIncludedTerm}.
 *
 * @author shenhongxi
 */
public class RaftInstallSnapshotRequest extends Request {

    private long term;
    private String leaderId;
    private long lastIncludedIndex;
    private long lastIncludedTerm;
    private String data;

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

    public long getLastIncludedIndex() {
        return lastIncludedIndex;
    }

    public void setLastIncludedIndex(long lastIncludedIndex) {
        this.lastIncludedIndex = lastIncludedIndex;
    }

    public long getLastIncludedTerm() {
        return lastIncludedTerm;
    }

    public void setLastIncludedTerm(long lastIncludedTerm) {
        this.lastIncludedTerm = lastIncludedTerm;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }
}