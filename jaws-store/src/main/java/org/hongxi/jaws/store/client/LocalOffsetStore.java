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
 * Client-managed offsets persisted to a local properties file. Keeping them
 * on the client removes a broker KV table and a commit protocol from the
 * engine scope while preserving restart-resume semantics.
 *
 * <p>RocketMQ splits this by message model: CLUSTERING uses
 * RemoteBrokerOffsetStore (broker-side config/consumerOffset.json, one
 * progress per group), BROADCASTING uses LocalFileOffsetStore (client-side
 * ~/.rocketmq_offsets/&lt;clientId&gt;/&lt;group&gt;/offsets.json, one progress per
 * instance). This store takes the group-scoped key of the former and the
 * client-local medium of the latter, so it is sound only while a group runs
 * a single instance; several instances per group need broker-side offsets,
 * as each would otherwise keep its own copy of shared progress.
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