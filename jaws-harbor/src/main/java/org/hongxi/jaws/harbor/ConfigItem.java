package org.hongxi.jaws.harbor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * One stored config: the key triple plus content and the md5 listeners compare
 * against. The md5 is computed once at publish time (MD5 hex of the UTF-8
 * content, the nacos {@code MD5Utils} convention), so a listen round never
 * re-hashes content.
 *
 * @author shenhongxi
 */
public class ConfigItem {

    private String dataId;

    private String group;

    private String tenant;

    private String content;

    private String md5;

    private long lastModified;

    public ConfigItem() {
    }

    public static ConfigItem of(String dataId, String group, String tenant, String content) {
        ConfigItem item = new ConfigItem();
        item.dataId = dataId;
        item.group = group;
        item.tenant = tenant;
        item.content = content;
        item.md5 = md5(content);
        item.lastModified = System.currentTimeMillis();
        return item;
    }

    /** MD5 hex of the content's UTF-8 bytes — what a nacos-client holds as md5. */
    public static String md5(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    public String getDataId() {
        return dataId;
    }

    public void setDataId(String dataId) {
        this.dataId = dataId;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getTenant() {
        return tenant;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
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
}