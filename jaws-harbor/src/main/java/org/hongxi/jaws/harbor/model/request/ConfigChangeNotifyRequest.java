package org.hongxi.jaws.harbor.model.request;

import org.hongxi.jaws.harbor.model.Request;

/**
 * Server→client push: notifies a config listener that a config has changed.
 *
 * @author shenhongxi
 */
public class ConfigChangeNotifyRequest extends Request {

    private String dataId;
    private String group;
    private String tenant;

    public String getDataId() {
        return dataId;
    }

    public void setDataId(String dataId) {
        this.dataId = dataId;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getTenant() {
        return tenant;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
    }
}
