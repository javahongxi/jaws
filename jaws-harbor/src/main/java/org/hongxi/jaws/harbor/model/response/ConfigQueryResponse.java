package org.hongxi.jaws.harbor.model.response;

import org.hongxi.jaws.harbor.model.Response;

/**
 * Response to {@link org.hongxi.jaws.harbor.model.request.ConfigQueryRequest}.
 *
 * @author shenhongxi
 */
public class ConfigQueryResponse extends Response {

    private String content;
    private String md5;
    private long lastModified;
    private String contentType;

    public ConfigQueryResponse() {
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getMd5() {
        return md5;
    }

    public void setMd5(String md5) {
        this.md5 = md5;
    }

    public long getLastModified() {
        return lastModified;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }
}
