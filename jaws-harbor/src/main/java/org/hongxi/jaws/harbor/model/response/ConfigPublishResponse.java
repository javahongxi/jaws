package org.hongxi.jaws.harbor.model.response;

import org.hongxi.jaws.harbor.model.Response;

/**
 * Reply to a {@code ConfigPublishRequest} — the nacos {@code ConfigPublishResponse}
 * shape: success is the whole payload, the content's md5 is learned by querying.
 *
 * @author shenhongxi
 */
public class ConfigPublishResponse extends Response {

    public ConfigPublishResponse() {
    }

    public static ConfigPublishResponse ok() {
        ConfigPublishResponse response = new ConfigPublishResponse();
        response.setResultCode(200);
        response.setSuccess(true);
        return response;
    }

    public static ConfigPublishResponse fail(String message) {
        ConfigPublishResponse response = new ConfigPublishResponse();
        response.setResultCode(500);
        // nacos buildFailResponse sets BOTH codes to FAIL(500); a lost CAS gets
        // no dedicated errorCode, its message text is the signal clients match.
        response.setErrorCode(500);
        response.setSuccess(false);
        response.setMessage(message);
        return response;
    }
}