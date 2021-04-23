package org.hongxi.jaws.config;

/**
 * Created by shenhongxi on 2021/4/23.
 */
public class BasicRefererInterfaceConfig extends AbstractRefererConfig {

    private static final long serialVersionUID = -418351068816874749L;
    /** 是否默认配置 */
    private Boolean isDefault;

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    public Boolean isDefault() {
        return isDefault;
    }
}