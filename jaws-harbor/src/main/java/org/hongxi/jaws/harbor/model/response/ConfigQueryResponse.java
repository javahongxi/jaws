package org.hongxi.jaws.harbor.model.response;

import org.hongxi.jaws.harbor.model.Response;

/**
 * Reply to a {@code ConfigQueryRequest} — the nacos {@code ConfigQueryResponse}
 * shape. A missing key is not an error body but {@link #CONFIG_NOT_FOUND} on
 * this very type: nacos-client reads that code as "the config is gone" and
 * hands its listeners a null content, which is how a removal reaches them.
 *
 * @author shenhongxi
 */
public class ConfigQueryResponse extends Response {

    /** Nacos {@code ConfigQueryResponse.CONFIG_NOT_FOUND}. */
    public static final int CONFIG_NOT_FOUND = 300;

    private String content;

    private String contentType;

    private String md5;

    public ConfigQueryResponse() {
    }

    public static ConfigQueryResponse ok(String content, String md5) {
        ConfigQueryResponse response = new ConfigQueryResponse();
        response.setResultCode(200);
        response.setSuccess(true);
        response.setContent(content);
        response.setContentType("text");
        response.setMd5(md5);
        return response;
    }

    public static ConfigQueryResponse notFound() {
        ConfigQueryResponse response = new ConfigQueryResponse();
        response.setResultCode(CONFIG_NOT_FOUND);
        // nacos-client reads the typed failure from errorCode, not resultCode;
        // without it a missing config reads as a generic error and getConfig
        // falls back to its local snapshot instead of reporting "gone".
        response.setErrorCode(CONFIG_NOT_FOUND);
        response.setSuccess(false);
        response.setMessage("config data not exist");
        return response;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getMd5() {
        return md5;
    }

    public void setMd5(String md5) {
        this.md5 = md5;
    }
}