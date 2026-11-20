package org.hongxi.jaws.harbor.raft;

import com.alibaba.fastjson2.JSON;
import org.hongxi.jaws.harbor.model.Instance;

import java.nio.charset.StandardCharsets;

/**
 * The state-machine command carried by every naming log entry — the harbor
 * counterpart of Nacos's {@code InstanceStoreRequest} wrapped in a
 * {@code WriteRequest}. REGISTER/DEREGISTER mirror the client verbs; HEALTH is
 * the server-side probe verdict, which Nacos also replicates through Raft
 * ({@code PersistentHealthStatusSynchronizer}); NOOP is the blank entry a new
 * leader appends so it can commit previous-term entries safely (paper Figure 8).
 *
 * @author shenhongxi
 */
public class NamingCommand {

    public static final String OP_REGISTER = "REGISTER";
    public static final String OP_DEREGISTER = "DEREGISTER";
    public static final String OP_HEALTH = "HEALTH";
    public static final String OP_NOOP = "NOOP";

    private String operation;
    private String namespace;
    private String group;
    private String serviceName;
    private String clientId;
    private Instance instance;

    public static NamingCommand register(String namespace, String group, String serviceName,
                                         String clientId, Instance instance) {
        return of(OP_REGISTER, namespace, group, serviceName, clientId, instance);
    }

    public static NamingCommand deregister(String namespace, String group, String serviceName,
                                           String clientId, Instance instance) {
        return of(OP_DEREGISTER, namespace, group, serviceName, clientId, instance);
    }

    public static NamingCommand health(String namespace, String group, String serviceName,
                                       String clientId, Instance instance) {
        return of(OP_HEALTH, namespace, group, serviceName, clientId, instance);
    }

    private static NamingCommand of(String operation, String namespace, String group,
                                    String serviceName, String clientId, Instance instance) {
        NamingCommand command = new NamingCommand();
        command.operation = operation;
        command.namespace = namespace;
        command.group = group;
        command.serviceName = serviceName;
        command.clientId = clientId;
        command.instance = instance;
        return command;
    }

    /** The blank command a fresh leader appends at the start of its term. */
    public static byte[] noopBytes() {
        NamingCommand noop = new NamingCommand();
        noop.operation = OP_NOOP;
        return JSON.toJSONBytes(noop);
    }

    public byte[] toBytes() {
        return JSON.toJSONBytes(this);
    }

    public static NamingCommand fromBytes(byte[] bytes) {
        return JSON.parseObject(new String(bytes, StandardCharsets.UTF_8), NamingCommand.class);
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public Instance getInstance() {
        return instance;
    }

    public void setInstance(Instance instance) {
        this.instance = instance;
    }
}