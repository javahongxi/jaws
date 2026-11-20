package org.hongxi.jaws.harbor.model.request;

/**
 * Delete one config — the nacos {@code ConfigRemoveRequest} shape (nacos spells
 * the verb "remove", not "delete"). {@code tag} is accepted but ignored, same
 * as on the query face.
 *
 * @author shenhongxi
 */
public class ConfigRemoveRequest extends AbstractConfigRequest {

    private String tag;

    public ConfigRemoveRequest() {
    }

    public ConfigRemoveRequest(String dataId, String group, String tenant) {
        setDataId(dataId);
        setGroup(group);
        setTenant(tenant);
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }
}