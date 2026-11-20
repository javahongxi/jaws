package org.hongxi.jaws.harbor.model.request;

/**
 * Read one config by its key — the nacos {@code ConfigQueryRequest} shape.
 * {@code tag} is accepted for wire compatibility but ignored: harbor keeps a
 * single version per key, with no tag/beta face.
 *
 * @author shenhongxi
 */
public class ConfigQueryRequest extends AbstractConfigRequest {

    private String tag;

    public ConfigQueryRequest() {
    }

    public static ConfigQueryRequest build(String dataId, String group, String tenant) {
        ConfigQueryRequest request = new ConfigQueryRequest();
        request.setDataId(dataId);
        request.setGroup(group);
        request.setTenant(tenant);
        return request;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }
}