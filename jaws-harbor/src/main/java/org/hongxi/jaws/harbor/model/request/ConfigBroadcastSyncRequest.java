package org.hongxi.jaws.harbor.model.request;

import org.hongxi.jaws.harbor.model.Request;

/**
 * Relays a dynamic-config broadcast from the node that received it to its
 * distro peers, so clients attached to any node see the change. Carries the
 * change itself: unlike nacos's cluster sync — which can afford a metadata-only
 * ping because a shared DB is the authority — a relay here has no authority to
 * read back from.
 *
 * @author shenhongxi
 */
public class ConfigBroadcastSyncRequest extends Request {

    private String key;

    private String value;

    private boolean deleted;

    public ConfigBroadcastSyncRequest() {
    }

    public ConfigBroadcastSyncRequest(String key, String value, boolean deleted) {
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
