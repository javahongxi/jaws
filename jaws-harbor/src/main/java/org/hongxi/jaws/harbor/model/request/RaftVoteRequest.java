package org.hongxi.jaws.harbor.model.request;

import org.hongxi.jaws.harbor.model.Request;

/**
 * Raft RequestVote RPC (paper §5.2): a candidate asks a peer for its vote in
 * {@code term}, presenting the tail of its log so the peer can apply the
 * election restriction — a voter only grants when the candidate's log is at
 * least as up-to-date as its own.
 *
 * @author shenhongxi
 */
public class RaftVoteRequest extends Request {

    private long term;
    private String candidateId;
    private long lastLogIndex;
    private long lastLogTerm;

    public long getTerm() {
        return term;
    }

    public void setTerm(long term) {
        this.term = term;
    }

    public String getCandidateId() {
        return candidateId;
    }

    public void setCandidateId(String candidateId) {
        this.candidateId = candidateId;
    }

    public long getLastLogIndex() {
        return lastLogIndex;
    }

    public void setLastLogIndex(long lastLogIndex) {
        this.lastLogIndex = lastLogIndex;
    }

    public long getLastLogTerm() {
        return lastLogTerm;
    }

    public void setLastLogTerm(long lastLogTerm) {
        this.lastLogTerm = lastLogTerm;
    }
}