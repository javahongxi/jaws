package org.hongxi.jaws;

import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.rpc.URL;

/**
 * Created by shenhongxi on 2021/4/21.
 */
public class MockServiceConfig<T> extends ServiceConfig<T> {
    private static final long serialVersionUID = 7965700855475224943L;

    protected boolean serviceExists(URL url) {
        return false;
    }
}