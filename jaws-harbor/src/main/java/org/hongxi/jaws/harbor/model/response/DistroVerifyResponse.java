package org.hongxi.jaws.harbor.model.response;

import org.hongxi.jaws.harbor.model.Response;

import java.util.List;

/**
 * Response to {@link org.hongxi.jaws.harbor.model.request.DistroVerifyRequest}.
 * <p>
 * When verification detects mismatches, {@code mismatchedConnectionIds} carries
 * the list of connectionIds that are missing or have revision mismatches on the
 * receiving side. The sender uses this for targeted snapshot repair.
 *
 * @author shenhongxi
 */
public class DistroVerifyResponse extends Response {

    private List<String> mismatchedConnectionIds;

    public List<String> getMismatchedClientIds() {
        return mismatchedConnectionIds;
    }

    public void setMismatchedClientIds(List<String> mismatchedConnectionIds) {
        this.mismatchedConnectionIds = mismatchedConnectionIds;
    }
}
