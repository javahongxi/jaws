package org.hongxi.jaws.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the broker-side offset table: the absence/present-0
 * distinction, monotonic commits under increaseOnly, deliberate overwrite for a
 * reset, and the atomic snapshot round-trip across a restart.
 */
class ConsumerOffsetStoreTest {

    @TempDir
    Path tempDir;

    /** A store with the flush ticker disabled; tests persist explicitly. */
    private ConsumerOffsetStore store(String name) {
        StoreConfig config = new StoreConfig();
        config.setStorePath(tempDir.resolve(name).toString());
        config.setBrokerName(name);
        config.setOffsetFlushIntervalMillis(0);
        ConsumerOffsetStore store = new ConsumerOffsetStore(config);
        store.start();
        return store;
    }

    @Test
    void absentIsDistinctFromZero() {
        ConsumerOffsetStore store = store("absence");
        try {
            assertNull(store.queryOffset("group-a", "demo", 0), "an untouched key must read as absent");

            store.updateOffset("group-a", "demo", 0, 0L, true);
            Long zero = store.queryOffset("group-a", "demo", 0);
            assertNotNull(zero, "a committed 0 is a stored position, not absence");
            assertEquals(0L, zero);
        } finally {
            store.shutdown();
        }
    }

    @Test
    void increaseOnlyKeepsTheHighestCommit() {
        ConsumerOffsetStore store = store("monotonic");
        try {
            assertEquals(5L, store.updateOffset("group-a", "demo", 1, 5L, true));
            // A lagging writer (an out-of-order RPC, or an instance that was
            // behind) must not undo progress the group already made.
            assertEquals(5L, store.updateOffset("group-a", "demo", 1, 3L, true));
            assertEquals(5L, store.queryOffset("group-a", "demo", 1));

            assertEquals(7L, store.updateOffset("group-a", "demo", 1, 7L, true));
            assertEquals(7L, store.queryOffset("group-a", "demo", 1));
        } finally {
            store.shutdown();
        }
    }

    /**
     * An offset reset moves backwards on purpose (the requested offset was
     * already reclaimed), so it has to overwrite the upward merge.
     */
    @Test
    void resetOverwritesWhenIncreaseOnlyIsOff() {
        ConsumerOffsetStore store = store("reset");
        try {
            store.updateOffset("group-a", "demo", 0, 9L, true);
            assertEquals(2L, store.updateOffset("group-a", "demo", 0, 2L, false));
            assertEquals(2L, store.queryOffset("group-a", "demo", 0));

            // After the reset the normal rule applies again.
            assertEquals(2L, store.updateOffset("group-a", "demo", 0, 1L, true));
        } finally {
            store.shutdown();
        }
    }

    @Test
    void groupsAndQueuesNeverCollide() {
        ConsumerOffsetStore store = store("keys");
        try {
            store.updateOffset("group-a", "demo", 0, 4L, true);
            store.updateOffset("group-b", "demo", 0, 8L, true);
            store.updateOffset("group-a", "demo", 1, 16L, true);
            store.updateOffset("group-a", "other", 0, 32L, true);

            assertEquals(4L, store.queryOffset("group-a", "demo", 0));
            assertEquals(8L, store.queryOffset("group-b", "demo", 0));
            assertEquals(16L, store.queryOffset("group-a", "demo", 1));
            assertEquals(32L, store.queryOffset("group-a", "other", 0));
            assertEquals(4, store.size());
        } finally {
            store.shutdown();
        }
    }

    @Test
    void snapshotSurvivesRestart() throws IOException {
        ConsumerOffsetStore first = store("snapshot");
        first.updateOffset("group-a", "demo", 0, 11L, true);
        first.updateOffset("group-a", "demo", 1, 22L, true);
        first.shutdown();

        Path file = tempDir.resolve("snapshot/config/consumerOffsets.properties");
        assertTrue(Files.exists(file), "shutdown must take a final snapshot");
        String content = Files.readString(file);
        assertTrue(content.contains("group-a@demo@0=11"), "unexpected snapshot body: " + content);

        ConsumerOffsetStore second = store("snapshot");
        try {
            assertEquals(11L, second.queryOffset("group-a", "demo", 0));
            assertEquals(22L, second.queryOffset("group-a", "demo", 1));
        } finally {
            second.shutdown();
        }
    }

    /**
     * A hand-edited or truncated line must not cost the whole table: the loader
     * drops the bad entry and keeps the rest.
     */
    @Test
    void malformedEntryIsSkippedWithoutLosingTheRest() throws IOException {
        StoreConfig config = new StoreConfig();
        config.setStorePath(tempDir.resolve("corrupt").toString());
        config.setOffsetFlushIntervalMillis(0);
        Path dir = tempDir.resolve("corrupt/config");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("consumerOffsets.properties"),
                "group-a@demo@0=5\ngroup-a@demo@1=not-a-number\n");

        ConsumerOffsetStore store = new ConsumerOffsetStore(config);
        store.start();
        try {
            assertEquals(5L, store.queryOffset("group-a", "demo", 0));
            assertNull(store.queryOffset("group-a", "demo", 1));
            assertEquals(1, store.size());
        } finally {
            store.shutdown();
        }
    }
}
