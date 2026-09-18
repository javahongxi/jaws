package org.hongxi.jaws.harbor.model.response;

import org.hongxi.jaws.harbor.model.Response;

/**
 * Client acknowledgement of a {@code ConnectResetRequest}: it heard the order and is
 * reconnecting. A response rather than a request because that is how Nacos names it,
 * and the token on the wire has to agree with what a nacos-client sends back.
 *
 * @author shenhongxi
 */
public class ConnectResetResponse extends Response {

    private boolean resetSuccess = true;

    public ConnectResetResponse() {
    }

    public boolean isResetSuccess() {
        return resetSuccess;
    }

    public void setResetSuccess(boolean resetSuccess) {
        this.resetSuccess = resetSuccess;
    }
}
