package org.hongxi.jaws.config;

import java.io.Serial;

/**
 * Created by shenhongxi on 2021/4/23.
 */
public class BasicReferenceInterfaceConfig extends AbstractReferenceConfig {

    @Serial
    private static final long serialVersionUID = -418351068816874749L;
    /**
     * 是否默认配置
     */
    private Boolean isDefault;

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    public Boolean isDefault() {
        return isDefault;
    }
}