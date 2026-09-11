package org.hongxi.jaws.harbor.model.request;

import java.util.Map;

/**
 * Periodic verify request: send checksums to a peer for consistency checking.
 *
 * @author shenhongxi
 */
public class DistroVerifyRequest extends DistroRequest {

    private Map<String, String> checksums;

    public Map<String, String> getChecksums() {
        return checksums;
    }

    public void setChecksums(Map<String, String> checksums) {
        this.checksums = checksums;
    }
}
