package org.hongxi.jaws.store;

import java.io.Serializable;

/**
 * Admin lookup result: a status plus, on a hit, the decoded message. Mirrors
 * the module's other wire results (PutResult, PullResult) — a status enum and a
 * payload — so NOT_FOUND travels as a normal response rather than a null or an
 * exception.
 */
public class AdminViewResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private ViewStatus status;
    private MessageView message;

    public AdminViewResult() {
    }

    public AdminViewResult(ViewStatus status, MessageView message) {
        this.status = status;
        this.message = message;
    }

    public static AdminViewResult found(MessageView message) {
        return new AdminViewResult(ViewStatus.FOUND, message);
    }

    public static AdminViewResult notFound() {
        return new AdminViewResult(ViewStatus.NOT_FOUND, null);
    }

    public boolean isFound() {
        return status == ViewStatus.FOUND;
    }

    public ViewStatus getStatus() {
        return status;
    }

    public void setStatus(ViewStatus status) {
        this.status = status;
    }

    public MessageView getMessage() {
        return message;
    }

    public void setMessage(MessageView message) {
        this.message = message;
    }
}
