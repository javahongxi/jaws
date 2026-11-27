package org.hongxi.jaws.store.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Client-side offset cache persisted to a local properties file. The broker
 * holds the authoritative record of a group's progress
 * ({@code ConsumerOffsetStore}); this file bootstraps a broker that has never
 * seen the group, carries progress across a window where the commit RPC failed,
 * and becomes the whole store when {@code StoreConsumer.setRemoteOffsets(false)}.
 *
 * <p>RocketMQ splits offset storage by message model: CLUSTERING uses
 * RemoteBrokerOffsetStore (broker-side config/consumerOffset.json, one progress
 * per group), BROADCASTING uses LocalFileOffsetStore (client-side
 * ~/.rocketmq_offsets/&lt;clientId&gt;/&lt;group&gt;/offsets.json, one progress per
 * instance). This module takes the clustering shape: a group has exactly one
 * shared position, no instance identity appears in any key, and the local file
 * is subordinate to the broker's copy — which is what lets a rebuilt container
 * or a second instance resume where the group left off instead of replaying.
 *
 * <p>Keys are brokerName@topic@queueId: in multi-master mode the same
 * (topic, queueId) exists on every broker with an independent offset space,
 * so the broker identity is part of the key. Pre-multi-instance files
 * (topic@queueId) no longer match and are ignored — consumption resumes
 * from 0 once after the upgrade.
 *
 * <p>Writes go through a temp file + atomic move so a crash never leaves a
 * half-written offset file.
 */
public class LocalOffsetStore {

    private static final Logger logger = LoggerFactory.getLogger(LocalOffsetStore.class);

    private final Path file;
    private final ConcurrentMap<String, Long> offsets = new ConcurrentHashMap<>();

    public LocalOffsetStore(String consumerGroup) {
        String baseDir = System.getProperty("jaws.store.client.path",
                System.getProperty("user.home") + File.separator + "jaws-store-client");
        this.file = new File(baseDir + File.separator + consumerGroup
                + File.separator + "offsets.properties").toPath();
    }

    public void load() {
        if (!Files.exists(file)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            logger.warn("Failed to load offsets from {}", file, e);
            return;
        }
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            offsets.put(String.valueOf(entry.getKey()), Long.parseLong(String.valueOf(entry.getValue())));
        }
        logger.info("Loaded {} consumer offsets from {}", offsets.size(), file);
    }

    public long get(String brokerName, String topic, int queueId) {
        return offsets.getOrDefault(key(brokerName, topic, queueId), 0L);
    }

    public void update(String brokerName, String topic, int queueId, long offset) {
        offsets.put(key(brokerName, topic, queueId), offset);
    }

    public void persist() {
        if (offsets.isEmpty()) {
            return;
        }
        Properties properties = new Properties();
        offsets.forEach((k, v) -> properties.setProperty(k, String.valueOf(v)));
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                properties.store(out, "jaws-store consumer offsets");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.warn("Failed to persist offsets to {}", file, e);
        }
    }

    private static String key(String brokerName, String topic, int queueId) {
        return brokerName + "@" + topic + "@" + queueId;
    }
}