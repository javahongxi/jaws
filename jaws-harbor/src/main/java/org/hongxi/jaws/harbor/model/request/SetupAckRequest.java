package org.hongxi.jaws.harbor.model.request;

import org.hongxi.jaws.harbor.model.Request;

import java.util.Map;

/**
 * Sent by the server through the BiRequestStream after processing a
 * {@link ConnectionSetupRequest}. Mirrors the Nacos {@code SetupAckRequest}
 * so that the nacos-client can recognise it as a {@code Request} (not a
 * {@code Response}) and handle it without a {@link ClassCastException}.
 * <p>
 * The client's bi-stream observer casts every incoming payload to
 * {@code Request}; sending a {@code Response} subclass would crash the
 * stream and trigger an infinite reconnection loop (~10 s cycle).
 *
 * @author shenhongxi
 */
public class SetupAckRequest extends Request {

    private Map<String, Boolean> abilityTable;

    public SetupAckRequest() {
    }

    public SetupAckRequest(Map<String, Boolean> abilityTable) {
        this.abilityTable = abilityTable;
    }

    public Map<String, Boolean> getAbilityTable() {
        return abilityTable;
    }

    public void setAbilityTable(Map<String, Boolean> abilityTable) {
        this.abilityTable = abilityTable;
    }
}
