package org.hongxi.jaws.harbor.model.request;

/**
 * Publish (create or overwrite) one config — the nacos {@code ConfigPublishRequest}
 * shape. {@code casMd5} is accepted for wire compatibility but ignored: harbor
 * publishes are last-write-wins through Raft, with no compare-and-swap face.
 *
 * @author shenhongxi
 */
public class ConfigPublishRequest extends AbstractConfigRequest {

    private String content;

    private String casMd5;

    public ConfigPublishRequest() {
    }

    public ConfigPublishRequest(String dataId, String group, String tenant, String content) {
        setDataId(dataId);
        setGroup(group);
        setTenant(tenant);
        this.content = content;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getCasMd5() {
        return casMd5;
    }

    public void setCasMd5(String casMd5) {
        this.casMd5 = casMd5;
    }
}