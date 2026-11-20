package org.hongxi.jaws.harbor.raft;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.TypeReference;
import org.hongxi.jaws.harbor.ClientSession;
import org.hongxi.jaws.harbor.ConfigChangeNotifier;
import org.hongxi.jaws.harbor.ConfigItem;
import org.hongxi.jaws.harbor.ConfigStorage;
import org.hongxi.jaws.harbor.ServiceStorage;
import org.hongxi.jaws.harbor.model.ClientSyncData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The state machine behind both CP faces — persistent instances and configs
 * share one raft group, so one machine applies both command dialects:
 * {@link NamingCommand}s mutate the same {@link ServiceStorage} dual-layer
 * indexes the ephemeral path uses (the Nacos {@code NacosStateMachine} +
 * {@code InstanceStoreRequest} replay shape), while {@link ConfigCommand}s
 * mutate {@link ConfigStorage} and notify this node's config listeners.
 * <p>
 * The snapshot image carries both faces: the naming part is a list of
 * {@link ClientSyncData} (one per persistent client) — the same shape Distro
 * syncs — and the config part is the stored {@link ConfigItem} table. Images
 * written before configs existed were a bare naming list; those still load.
 *
 * @author shenhongxi
 */
public class HarborStateMachine implements StateMachine {

    private static final Logger log = LoggerFactory.getLogger(HarborStateMachine.class);

    private final ServiceStorage serviceStorage;
    private final ConfigStorage configStorage;
    private final ConfigChangeNotifier configChangeNotifier;

    public HarborStateMachine(ServiceStorage serviceStorage, ConfigStorage configStorage,
                              ConfigChangeNotifier configChangeNotifier) {
        this.serviceStorage = serviceStorage;
        this.configStorage = configStorage;
        this.configChangeNotifier = configChangeNotifier;
    }

    @Override
    public void onApply(LogEntry entry) {
        if (entry.data() == null || entry.data().length == 0) {
            return;
        }
        JSONObject json = JSON.parseObject(new String(entry.data(), StandardCharsets.UTF_8));
        String operation = json.getString("operation");
        if (operation != null && operation.startsWith("CONFIG_")) {
            applyConfig(json.toJavaObject(ConfigCommand.class));
        } else {
            applyNaming(json.toJavaObject(NamingCommand.class));
        }
    }

    private void applyNaming(NamingCommand command) {
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

    private void applyConfig(ConfigCommand command) {
        switch (command.getOperation()) {
            case ConfigCommand.OP_CONFIG_PUBLISH -> configStorage.publish(ConfigItem.of(
                    command.getDataId(), command.getGroup(), command.getTenant(),
                    command.getContent()));
            case ConfigCommand.OP_CONFIG_REMOVE -> configStorage.remove(
                    command.getDataId(), command.getGroup(), command.getTenant());
            default -> {
                log.warn("[raft] unknown config command operation: {}", command.getOperation());
                return;
            }
        }
        // Every node applies the same entry and notifies its own listeners;
        // the push needs no inter-node relay.
        configChangeNotifier.requestNotify(
                command.getDataId(), command.getGroup(), command.getTenant());
    }

    @Override
    public byte[] saveSnapshot() {
        List<ClientSyncData> naming = new ArrayList<>();
        for (ClientSession session : serviceStorage.allPersistentSessions()) {
            ClientSyncData data = serviceStorage.buildClientSyncData(session.getConnectionId());
            if (data != null) {
                naming.add(data);
            }
        }
        List<ConfigItem> configs = List.copyOf(configStorage.allConfigs());
        return JSON.toJSONBytes(new SnapshotImage(naming, configs));
    }

    @Override
    public void loadSnapshot(byte[] data) {
        String json = new String(data, StandardCharsets.UTF_8).stripLeading();
        if (json.startsWith("[")) {
            // Pre-config image: a bare naming list.
            List<ClientSyncData> naming = JSON.parseObject(json, new TypeReference<>() {});
            serviceStorage.loadPersistentSnapshot(naming);
            return;
        }
        SnapshotImage image = JSON.parseObject(json, SnapshotImage.class);
        serviceStorage.loadPersistentSnapshot(image.naming());
        configStorage.loadSnapshot(image.configs());
    }

    /** Both CP faces in one image, so one log position covers the whole state. */
    public record SnapshotImage(List<ClientSyncData> naming, List<ConfigItem> configs) {
    }
}