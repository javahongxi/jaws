package org.hongxi.jaws.harbor.model.request;

import org.hongxi.jaws.harbor.model.Request;

/**
 * Server-initiated broadcast of one dynamic-config change, pushed over the bi-stream
 * to every connected {@code jaws} client. Not a Nacos type: harbor does not store
 * configuration — this is a demo affordance letting an operator push a framework
 * setting (timeout, route rule, toggle) to live clients, each of which applies it to
 * its own in-process {@code LocalDynamicConfiguration} and fires its listeners.
 * <p>
 * Fire-and-forget: no ack, no catch-up. A client only sees a change if it is
 * connected when the broadcast goes out, and a client restart reverts to defaults
 * until the next push. Removal is signalled by {@code deleted=true}, not a null
 * value, so the wire never carries an ambiguous payload.
 *
 * @author shenhongxi
 */
public class DynamicConfigChangeRequest extends Request {

    private String key;
    private String value;
    private boolean deleted;

    public DynamicConfigChangeRequest() {
    }

    public DynamicConfigChangeRequest(String key, String value, boolean deleted) {
        this.key = key;
        this.value = value;
        this.deleted = deleted;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }
}
