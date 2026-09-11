package org.hongxi.jaws.harbor.model.request;

import org.hongxi.jaws.harbor.model.Request;

import java.util.Map;

/**
 * Sent by nacos-client over the BiRequestStream to register
 * client metadata (version, labels) after ServerCheck.
 *
 * @author shenhongxi
 */
public class ConnectionSetupRequest extends Request {

    private String clientVersion;
    private Map<String, String> labels;
    private Map<String, Boolean> abilityTable;
    private String tenant;

    public String getClientVersion() {
        return clientVersion;
    }

    public void setClientVersion(String clientVersion) {
        this.clientVersion = clientVersion;
    }

    public Map<String, String> getLabels() {
        return labels;
    }

    public void setLabels(Map<String, String> labels) {
        this.labels = labels;
    }

    public Map<String, Boolean> getAbilityTable() {
        return abilityTable;
    }

    public void setAbilityTable(Map<String, Boolean> abilityTable) {
        this.abilityTable = abilityTable;
    }

    public String getTenant() {
        return tenant;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
    }
}
