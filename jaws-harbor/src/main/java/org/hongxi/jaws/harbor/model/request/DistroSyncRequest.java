package org.hongxi.jaws.harbor.model.request;

import org.hongxi.jaws.harbor.model.Request;

/**
 * Sync a data change to a peer node.
 * The {@code content} field is Base64-encoded serialized data.
 *
 * @author shenhongxi
 */
public class DistroSyncRequest extends Request {

    private String resourceKey;
    private String operation;
    private String content;

    public String getResourceKey() {
        return resourceKey;
    }

    public void setResourceKey(String resourceKey) {
        this.resourceKey = resourceKey;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
