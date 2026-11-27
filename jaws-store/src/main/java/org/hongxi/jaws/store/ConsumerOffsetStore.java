package org.hongxi.jaws.store;

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
 * Broker-side consumer offsets: group@topic@queueId to the next offset to pull.
 * This is the authoritative record of a group's progress — RocketMQ's
 * ConsumerOffsetManager role — so a fresh consumer instance (a rebuilt container,
 * another host, a second process) resumes where the group actually left off
 * instead of replaying the whole queue. No client-side copy backs it up: this
 * table is the only place a group's position survives a restart.
 *
 * <p>Deliberately kept out of {@link MessageStore}: offsets are consumer
 * metadata, not log state. Nothing here is touched by the put or pull path, it
 * never participates in the durability barrier, and losing the file costs
 * replay, not data. That separation is also why its own small snapshot is the
 * right storage shape; a KV engine like RocketMQ's ConsumerOffsetManagerV2 would
 * buy nothing at this scale.
 *
 * <p>Updates are applied under {@code merge}, so the table stays monotonic per
 * key without a lock: with {@code increaseOnly} a stale writer can never rewind
 * a group that has already moved ahead. Reset paths pass {@code false} to
 * overwrite deliberately.
 *
 * <p>Persistence is a properties file written through a temp file plus an atomic
 * move rather than RocketMQ's JSON snapshot, so a crash never leaves a
 * half-written offset table behind.
 */
public class ConsumerOffsetStore {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerOffsetStore.class);

    private final StoreConfig config;
    private final ConcurrentMap<String, Long> offsetTable = new ConcurrentHashMap<>();
    private volatile boolean stopped;
    private Thread flushThread;

    public ConsumerOffsetStore(StoreConfig config) {
        this.config = config;
    }

    /** Load the snapshot (if any) and start the periodic flush ticker. */
    public void start() {
        load();
        int interval = config.getOffsetFlushIntervalMillis();
        if (interval <= 0) {
            return;
        }
        flushThread = new Thread(() -> {
            while (!stopped) {
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                try {
                    persist();
                } catch (Exception e) {
                    // One failed snapshot must not stop the group from tracking progress.
                    logger.warn("Offset snapshot failed, retry next interval", e);
                }
            }
        }, "StoreOffsetFlush");
        flushThread.setDaemon(true);
        flushThread.start();
    }

    /**
     * @return the stored offset, or null when this broker has never seen the key.
     *         Absence is not 0: 0 is a real position.
     */
    public Long queryOffset(String group, String topic, int queueId) {
        return offsetTable.get(key(group, topic, queueId));
    }

    /**
     * Record a group's progress.
     *
     * @param increaseOnly true for the ordinary commit path — a lower value is
     *                     then dropped, so a lagging writer cannot undo progress
     *                     the group already made; false overwrites, for a
     *                     deliberate reset (an offset the log rejected as illegal)
     * @return the authoritative offset after the update, which is the caller's
     *         value only when it won
     */
    public long updateOffset(String group, String topic, int queueId, long offset, boolean increaseOnly) {
        String key = key(group, topic, queueId);
        offsetTable.merge(key, offset,
                (existing, incoming) -> increaseOnly ? Math.max(existing, incoming) : incoming);
        return offsetTable.get(key);
    }

    /** Number of tracked group@topic@queueId entries (observability, tests). */
    public int size() {
        return offsetTable.size();
    }

    public void persist() {
        if (offsetTable.isEmpty()) {
            return;
        }
        Path file = snapshotFile();
        Properties properties = new Properties();
        offsetTable.forEach((k, v) -> properties.setProperty(k, String.valueOf(v)));
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                properties.store(out, "jaws-store broker-side consumer offsets");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.warn("Failed to persist offsets to {}", file, e);
        }
    }

    private void load() {
        Path file = snapshotFile();
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
            try {
                offsetTable.put(String.valueOf(entry.getKey()), Long.parseLong(String.valueOf(entry.getValue())));
            } catch (NumberFormatException e) {
                logger.warn("Ignoring malformed offset entry {}={}", entry.getKey(), entry.getValue());
            }
        }
        logger.info("Loaded {} consumer offsets from {}", offsetTable.size(), file);
    }

    /** Stop the ticker and take a final snapshot. */
    public void shutdown() {
        stopped = true;
        if (flushThread != null) {
            flushThread.interrupt();
        }
        persist();
    }

    private Path snapshotFile() {
        return new File(config.getStorePath() + File.separator + "config"
                + File.separator + "consumerOffsets.properties").toPath();
    }

    private static String key(String group, String topic, int queueId) {
        return group + "@" + topic + "@" + queueId;
    }
}
