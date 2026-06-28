package org.hongxi.jaws.config;

import java.io.Serial;

/**
 * Created by shenhongxi on 2021/3/6.
 */
public class BasicServiceInterfaceConfig extends AbstractServiceConfig {

    @Serial
    private static final long serialVersionUID = 12689211620290427L;
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