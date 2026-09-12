package org.hongxi.jaws.harbor.model.response;

import org.hongxi.jaws.harbor.model.Response;

/**
 * Response to {@link org.hongxi.jaws.harbor.model.request.DistroSnapshotRequest}.
 * The {@code content} field is Base64-encoded snapshot data.
 *
 * @author shenhongxi
 */
public class DistroSnapshotResponse extends Response {

    private String content;

    public DistroSnapshotResponse() {
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
