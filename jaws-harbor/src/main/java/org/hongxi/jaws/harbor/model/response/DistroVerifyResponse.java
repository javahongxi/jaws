package org.hongxi.jaws.harbor.model.response;

import org.hongxi.jaws.harbor.model.Response;

import java.util.List;

/**
 * Response to {@link org.hongxi.jaws.harbor.model.request.DistroVerifyRequest}.
 * <p>
 * When verification detects mismatches, {@code mismatchedClientIds} carries
 * the list of clientIds that are missing or have revision mismatches on the
 * receiving side. The sender uses this for targeted snapshot repair.
 *
 * @author shenhongxi
 */
public class DistroVerifyResponse extends Response {

    private List<String> mismatchedClientIds;

    public List<String> getMismatchedClientIds() {
        return mismatchedClientIds;
    }

    public void setMismatchedClientIds(List<String> mismatchedClientIds) {
        this.mismatchedClientIds = mismatchedClientIds;
    }
}
