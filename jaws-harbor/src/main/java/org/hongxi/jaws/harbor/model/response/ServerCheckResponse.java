package org.hongxi.jaws.harbor.model.response;

import org.hongxi.jaws.harbor.model.Response;

/**
 * Response to {@link org.hongxi.jaws.harbor.model.request.ServerCheckRequest}.
 *
 * @author shenhongxi
 */
public class ServerCheckResponse extends Response {

    private String connectionId;
    private boolean supportAbilityNegotiation;

    public ServerCheckResponse() {
    }

    public String getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(String connectionId) {
        this.connectionId = connectionId;
    }

    public boolean isSupportAbilityNegotiation() {
        return supportAbilityNegotiation;
    }

    public void setSupportAbilityNegotiation(boolean supportAbilityNegotiation) {
        this.supportAbilityNegotiation = supportAbilityNegotiation;
    }
}
