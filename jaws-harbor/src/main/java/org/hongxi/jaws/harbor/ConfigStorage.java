package org.hongxi.jaws.harbor;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The config face's source of truth — an in-memory table keyed by
 * {@code dataId+group+tenant}, the harbor counterpart of nacos's
 * {@code ConfigCacheService} minus the database: writes arrive ONLY as
 * committed Raft commands applied by {@code HarborStateMachine}, durability
 * comes from the raft log/snapshot, and every node holds the same table.
 * Reads are concurrent and served straight from this node's memory
 * (eventually consistent across the cluster, which config reads tolerate).
 *
 * @author shenhongxi
 */
public class ConfigStorage {

    private final Map<String, ConfigItem> configs = new ConcurrentHashMap<>();

    /**
     * The flat key nacos's {@code GroupKey2} builds: the three parts joined by
     * {@code '+'}. Used verbatim so a listen context and a stored item always
     * agree on the key.
     */
    public static String groupKey(String dataId, String group, String tenant) {
        return dataId + "+" + group + "+" + tenant;
    }

    /** Apply a committed CONFIG_PUBLISH. Raft apply thread only. */
    public void publish(ConfigItem item) {
        configs.put(groupKey(item.getDataId(), item.getGroup(), item.getTenant()), item);
    }

    /** Apply a committed CONFIG_REMOVE. Raft apply thread only. */
    public void remove(String dataId, String group, String tenant) {
        configs.remove(groupKey(dataId, group, tenant));
    }

    public ConfigItem get(String dataId, String group, String tenant) {
        return configs.get(groupKey(dataId, group, tenant));
    }

    public Collection<ConfigItem> allConfigs() {
        return List.copyOf(configs.values());
    }

    /** Replace the whole table with a snapshot image (recovery/InstallSnapshot). */
    public void loadSnapshot(List<ConfigItem> image) {
        configs.clear();
        for (ConfigItem item : image) {
            configs.put(groupKey(item.getDataId(), item.getGroup(), item.getTenant()), item);
        }
    }
}