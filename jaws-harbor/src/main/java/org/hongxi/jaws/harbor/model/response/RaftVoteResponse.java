package org.hongxi.jaws.harbor.model.response;

import org.hongxi.jaws.harbor.model.Response;

/**
 * Reply to {@code RaftVoteRequest}. {@code term} is the voter's current term so
 * the candidate can step down when it discovers a newer one; {@code voteGranted}
 * carries the vote itself.
 *
 * @author shenhongxi
 */
public class RaftVoteResponse extends Response {

    private long term;
    private boolean voteGranted;

    public long getTerm() {
        return term;
    }

    public void setTerm(long term) {
        this.term = term;
    }

    public boolean isVoteGranted() {
        return voteGranted;
    }

    public void setVoteGranted(boolean voteGranted) {
        this.voteGranted = voteGranted;
    }
}