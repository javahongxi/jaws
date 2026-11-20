package org.hongxi.jaws.harbor.raft;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import org.hongxi.jaws.harbor.ClientSession;
import org.hongxi.jaws.harbor.ServiceStorage;
import org.hongxi.jaws.harbor.model.ClientSyncData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The naming state machine behind persistent instances — the harbor
 * counterpart of Nacos's {@code NacosStateMachine} + {@code InstanceStoreRequest}
 * replay: committed {@link NamingCommand}s mutate the same
 * {@link ServiceStorage} dual-layer indexes the ephemeral path uses, so reads
 * and subscriber pushes stay protocol-agnostic.
 * <p>
 * The snapshot image is a list of {@link ClientSyncData} (one per persistent
 * client) — the same shape Distro syncs, reused here so the CP snapshot and
 * the AP snapshot speak one data dialect while never mixing contents.
 *
 * @author shenhongxi
 */
public class NamingStateMachine implements StateMachine {

    private static final Logger log = LoggerFactory.getLogger(NamingStateMachine.class);

    private final ServiceStorage serviceStorage;

    public NamingStateMachine(ServiceStorage serviceStorage) {
        this.serviceStorage = serviceStorage;
    }

    @Override
    public void onApply(LogEntry entry) {
        if (entry.data() == null || entry.data().length == 0) {
            return;
        }
        NamingCommand command = NamingCommand.fromBytes(entry.data());
        switch (command.getOperation()) {
            case NamingCommand.OP_REGISTER -> serviceStorage.registerPersistentInstance(
                    command.getNamespace(), command.getGroup(), command.getServiceName(),
                    command.getClientId(), command.getInstance());
            case NamingCommand.OP_DEREGISTER -> serviceStorage.deregisterPersistentInstance(
                    command.getNamespace(), command.getGroup(), command.getServiceName(),
                    command.getClientId(), command.getInstance());
            case NamingCommand.OP_HEALTH -> serviceStorage.updatePersistentHealth(
                    command.getNamespace(), command.getGroup(), command.getServiceName(),
                    command.getClientId(), command.getInstance());
            case NamingCommand.OP_NOOP -> {
                // The new-leader blank entry: commits previous-term entries, changes nothing.
            }
            default -> log.warn("[raft] unknown naming command operation: {}", command.getOperation());
        }
    }

    @Override
    public byte[] saveSnapshot() {
        List<ClientSyncData> image = new ArrayList<>();
        for (ClientSession session : serviceStorage.allPersistentSessions()) {
            ClientSyncData data = serviceStorage.buildClientSyncData(session.getConnectionId());
            if (data != null) {
                image.add(data);
            }
        }
        return JSON.toJSONBytes(image);
    }

    @Override
    public void loadSnapshot(byte[] data) {
        List<ClientSyncData> image = JSON.parseObject(
                new String(data, StandardCharsets.UTF_8), new TypeReference<>() {});
        serviceStorage.loadPersistentSnapshot(image);
    }
}