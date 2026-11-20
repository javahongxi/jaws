package org.hongxi.jaws.harbor.model.response;

import org.hongxi.jaws.harbor.model.Response;

/**
 * Reply to {@code RaftWriteForwardRequest}. When the target is no longer the
 * leader, {@code leaderId} carries its best-known leader hint so the relaying
 * node can retry once against the right node; {@code message} names the failure.
 *
 * @author shenhongxi
 */
public class RaftWriteForwardResponse extends Response {

    private String leaderId;

    public String getLeaderId() {
        return leaderId;
    }

    public void setLeaderId(String leaderId) {
        this.leaderId = leaderId;
    }
}