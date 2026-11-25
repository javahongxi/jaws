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
 * Client-managed offsets persisted to a local properties file — the Kafka
 * style, a declared deviation from RocketMQ's broker-side offset store.
 * It removes a broker KV table and a commit protocol from the engine scope
 * while keeping restart-resume semantics.
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

    public long get(String topic, int queueId) {
        return offsets.getOrDefault(key(topic, queueId), 0L);
    }

    public void update(String topic, int queueId, long offset) {
        offsets.put(key(topic, queueId), offset);
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

    private static String key(String topic, int queueId) {
        return topic + "@" + queueId;
    }
}