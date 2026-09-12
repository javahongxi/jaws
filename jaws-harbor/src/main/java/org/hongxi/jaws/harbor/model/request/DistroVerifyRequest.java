package org.hongxi.jaws.harbor.model.request;

import org.hongxi.jaws.harbor.model.ClientVerifyInfo;

import java.util.List;

/**
 * Periodic verify request: send per-client revision data to a peer for
 * consistency checking.
 *
 * @author shenhongxi
 */
public class DistroVerifyRequest extends DistroRequest {

    private List<ClientVerifyInfo> verifyInfos;

    public List<ClientVerifyInfo> getVerifyInfos() {
        return verifyInfos;
    }

    public void setVerifyInfos(List<ClientVerifyInfo> verifyInfos) {
        this.verifyInfos = verifyInfos;
    }
}
