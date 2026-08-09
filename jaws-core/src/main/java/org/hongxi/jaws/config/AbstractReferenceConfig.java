package org.hongxi.jaws.config;

import java.io.Serial;

/**
 * Created by shenhongxi on 2021/4/23.
 */
public abstract class AbstractReferenceConfig extends AbstractInterfaceConfig {

    @Serial
    private static final long serialVersionUID = -8953815191278008453L;

    protected Boolean asyncInitConnection;

    public Boolean getAsyncInitConnection() {
        return asyncInitConnection;
    }

    public void setAsyncInitConnection(Boolean asyncInitConnection) {
        this.asyncInitConnection = asyncInitConnection;
    }

}