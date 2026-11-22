package org.hongxi.jaws.harbor.raft;

import com.alibaba.fastjson2.JSON;

import java.nio.charset.StandardCharsets;

/**
 * The state-machine command carried by every config log entry. PUBLISH/REMOVE
 * mirror the client verbs; both ride the SAME raft group as naming commands —
 * one leader, one log, one apply order — and {@code HarborStateMachine} tells
 * the two dialects apart by the {@code CONFIG_} operation prefix.
 *
 * @author shenhongxi
 */
public class ConfigCommand {

    public static final String OP_CONFIG_PUBLISH = "CONFIG_PUBLISH";
    public static final String OP_CONFIG_REMOVE = "CONFIG_REMOVE";

    private String operation;
    private String tenant;
    private String group;
    private String dataId;
    private String content;
    /** Expected current md5 for a CAS publish; null/blank writes unconditionally.
     * Absent in log entries written before CAS existed, which read as null. */
    private String casMd5;

    public static ConfigCommand publish(String tenant, String group, String dataId, String content) {
        return publish(tenant, group, dataId, content, null);
    }

    public static ConfigCommand publish(String tenant, String group, String dataId,
                                        String content, String casMd5) {
        ConfigCommand command = of(OP_CONFIG_PUBLISH, tenant, group, dataId, content);
        command.casMd5 = casMd5;
        return command;
    }

    public static ConfigCommand remove(String tenant, String group, String dataId) {
        return of(OP_CONFIG_REMOVE, tenant, group, dataId, null);
    }

    private static ConfigCommand of(String operation, String tenant, String group,
                                    String dataId, String content) {
        ConfigCommand command = new ConfigCommand();
        command.operation = operation;
        command.tenant = tenant;
        command.group = group;
        command.dataId = dataId;
        command.content = content;
        return command;
    }

    public byte[] toBytes() {
        return JSON.toJSONBytes(this);
    }

    public static ConfigCommand fromBytes(byte[] bytes) {
        return JSON.parseObject(new String(bytes, StandardCharsets.UTF_8), ConfigCommand.class);
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getTenant() {
        return tenant;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getDataId() {
        return dataId;
    }

    public void setDataId(String dataId) {
        this.dataId = dataId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getCasMd5() {
        return casMd5;
    }

    public void setCasMd5(String casMd5) {
        this.casMd5 = casMd5;
    }
}