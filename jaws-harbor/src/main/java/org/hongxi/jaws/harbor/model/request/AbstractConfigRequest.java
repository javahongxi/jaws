package org.hongxi.jaws.harbor.model.request;

import org.hongxi.jaws.harbor.model.Request;

/**
 * Base of every config request, mirroring nacos's {@code AbstractConfigRequest}:
 * a config is addressed by {@code dataId + group + tenant}, where tenant is the
 * config face's name for a namespace.
 *
 * @author shenhongxi
 */
public abstract class AbstractConfigRequest extends Request {

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