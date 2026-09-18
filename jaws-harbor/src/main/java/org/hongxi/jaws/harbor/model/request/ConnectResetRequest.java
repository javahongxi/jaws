package org.hongxi.jaws.harbor.model.request;

import org.hongxi.jaws.harbor.model.Request;

/**
 * Server-initiated "drop this connection and reconnect", optionally somewhere else.
 * Nacos uses it to shed load or take a node out of service; the field names are its
 * own, so a real nacos-client reads it the same way.
 *
 * @author shenhongxi
 */
public class ConnectResetRequest extends Request {

    private String serverIp;
    private String serverPort;
    private String connectionId;

    public ConnectResetRequest() {
    }

    public ConnectResetRequest(String serverIp, String serverPort, String connectionId) {
        this.serverIp = serverIp;
        this.serverPort = serverPort;
        this.connectionId = connectionId;
    }

    public String getServerIp() {
        return serverIp;
    }

    public void setServerIp(String serverIp) {
        this.serverIp = serverIp;
    }

    public String getServerPort() {
        return serverPort;
    }

    public void setServerPort(String serverPort) {
        this.serverPort = serverPort;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(String connectionId) {
        this.connectionId = connectionId;
    }
}
