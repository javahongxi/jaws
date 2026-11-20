package org.hongxi.jaws.harbor.model.request;

/**
 * Server-initiated notice that one config changed, pushed over the bi-stream —
 * the nacos {@code ConfigChangeNotifyRequest} shape. It carries only the key:
 * the client answers with a {@code ConfigChangeNotifyResponse} ack and then
 * queries the new content, so a dropped notice is healed by the next listen
 * round rather than by resending content.
 *
 * @author shenhongxi
 */
public class ConfigChangeNotifyRequest extends AbstractConfigRequest {

    public ConfigChangeNotifyRequest() {
    }

    public static ConfigChangeNotifyRequest build(String dataId, String group, String tenant) {
        ConfigChangeNotifyRequest request = new ConfigChangeNotifyRequest();
        request.setDataId(dataId);
        request.setGroup(group);
        request.setTenant(tenant);
        return request;
    }
}