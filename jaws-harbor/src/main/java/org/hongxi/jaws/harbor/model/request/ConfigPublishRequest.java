package org.hongxi.jaws.harbor.model.request;

import java.util.Map;

/**
 * Publish a configuration item.
 *
 * @author shenhongxi
 */
public class ConfigPublishRequest extends ConfigRequest {

    private String content;
    private Map<String, String> additionMap;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Map<String, String> getAdditionMap() {
        return additionMap;
    }

    public void setAdditionMap(Map<String, String> additionMap) {
        this.additionMap = additionMap;
    }
}
