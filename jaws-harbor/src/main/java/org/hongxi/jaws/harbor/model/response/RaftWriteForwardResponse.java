package org.hongxi.jaws.harbor.model.response;

import org.hongxi.jaws.harbor.model.Response;

/**
 * Reply to {@code RaftWriteForwardRequest}. When the target is no longer the
 * leader, {@code leaderId} carries its best-known leader hint so the relaying
 * node can retry once against the right node; {@code message} names the failure.
 * {@code refusal} is the one failure that must NOT be retried: the entry
 * committed and applied but the state machine refused it (a lost CAS), a
 * deterministic outcome the origin node surfaces as-is.
 *
 * @author shenhongxi
 */
public class RaftWriteForwardResponse extends Response {

    private String leaderId;

    private String refusal;

    public String getLeaderId() {
        return leaderId;
    }

    public void setLeaderId(String leaderId) {
        this.leaderId = leaderId;
    }

    public String getRefusal() {
        return refusal;
    }

    public void setRefusal(String refusal) {
        this.refusal = refusal;
    }
}