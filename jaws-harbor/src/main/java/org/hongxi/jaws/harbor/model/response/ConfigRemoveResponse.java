package org.hongxi.jaws.harbor.model.response;

import org.hongxi.jaws.harbor.model.Response;

/**
 * Reply to a {@code ConfigRemoveRequest} — the nacos {@code ConfigRemoveResponse}
 * shape. Removing a key that does not exist still succeeds, matching nacos's
 * idempotent delete.
 *
 * @author shenhongxi
 */
public class ConfigRemoveResponse extends Response {

    public ConfigRemoveResponse() {
    }

    public static ConfigRemoveResponse ok() {
        ConfigRemoveResponse response = new ConfigRemoveResponse();
        response.setResultCode(200);
        response.setSuccess(true);
        return response;
    }

    public static ConfigRemoveResponse fail(String message) {
        ConfigRemoveResponse response = new ConfigRemoveResponse();
        response.setResultCode(500);
        response.setSuccess(false);
        response.setMessage(message);
        return response;
    }
}