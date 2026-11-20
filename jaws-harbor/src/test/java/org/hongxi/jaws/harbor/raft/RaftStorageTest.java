package org.hongxi.jaws.harbor.raft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RaftStorage}: durable hard state, append-only log with
 * torn-tail tolerance, and the snapshot/rewrite compaction pair.
 *
 * @author shenhongxi
 */
class RaftStorageTest {

    @TempDir
    Path dir;

    private static LogEntry entry(long index, long term) {
        return new LogEntry(index, term, ("cmd-" + index).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void freshNodeHasNoDurableState() {
        RaftStorage storage = new RaftStorage(dir);
        assertNull(storage.loadMeta());
        assertNull(storage.loadSnapshot());
        assertTrue(storage.loadLog(0).isEmpty());
        storage.close();
    }

    @Test
    void metaSurvivesReopen() {
        RaftStorage storage = new RaftStorage(dir);
        storage.saveMeta(7, "127.0.0.1:1001");
        storage.close();

        RaftStorage reopened = new RaftStorage(dir);
        RaftStorage.Meta meta = reopened.loadMeta();
        assertEquals(7, meta.currentTerm());
        assertEquals("127.0.0.1:1001", meta.votedFor());
        reopened.close();
    }

    @Test
    void logAppendIsDurableAndOrdered() {
        RaftStorage storage = new RaftStorage(dir);
        storage.appendLog(List.of(entry(1, 1), entry(2, 1)));
        storage.appendLog(List.of(entry(3, 2)));
        storage.close();

        RaftStorage reopened = new RaftStorage(dir);
        List<LogEntry> recovered = reopened.loadLog(0);
        assertEquals(3, recovered.size());
        assertEquals(3, recovered.get(2).index());
        assertEquals(2, recovered.get(2).term());
        assertArrayEquals("cmd-3".getBytes(StandardCharsets.UTF_8), recovered.get(2).data());
        reopened.close();
    }

    @Test
    void tornTailLineIsDroppedNotFatal() throws IOException {
        RaftStorage storage = new RaftStorage(dir);
        storage.appendLog(List.of(entry(1, 1)));
        storage.close();
        // A crash mid-append leaves half a JSON line; it was never acknowledged.
        Files.writeString(dir.resolve("log.jsonl"), "{\"index\":2,\"ter",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        RaftStorage reopened = new RaftStorage(dir);
        List<LogEntry> recovered = reopened.loadLog(0);
        assertEquals(1, recovered.size(), "only the fully written entry survives");
        reopened.close();
    }

    @Test
    void loadLogSkipsTheCompactedPrefix() {
        RaftStorage storage = new RaftStorage(dir);
        storage.appendLog(List.of(entry(1, 1), entry(2, 1), entry(3, 2)));
        List<LogEntry> suffix = storage.loadLog(2);
        assertEquals(1, suffix.size());
        assertEquals(3, suffix.get(0).index());
        storage.close();
    }

    @Test
    void rewriteReplacesTheWholeLogFile() {
        RaftStorage storage = new RaftStorage(dir);
        storage.appendLog(List.of(entry(1, 1), entry(2, 1), entry(3, 1)));
        storage.rewriteLog(List.of(entry(3, 2)));
        // The append handle must keep working after a rewrite.
        storage.appendLog(List.of(entry(4, 2)));
        storage.close();

        RaftStorage reopened = new RaftStorage(dir);
        List<LogEntry> recovered = reopened.loadLog(0);
        assertEquals(2, recovered.size());
        assertEquals(3, recovered.get(0).index());
        assertEquals(2, recovered.get(0).term());
        assertEquals(4, recovered.get(1).index());
        reopened.close();
    }

    @Test
    void snapshotRoundTripsWithItsBoundary() {
        RaftStorage storage = new RaftStorage(dir);
        byte[] image = "{\"state\":42}".getBytes(StandardCharsets.UTF_8);
        storage.saveSnapshot(9, 3, image);
        storage.close();

        RaftStorage reopened = new RaftStorage(dir);
        RaftStorage.Snapshot snapshot = reopened.loadSnapshot();
        assertEquals(9, snapshot.lastIncludedIndex());
        assertEquals(3, snapshot.lastIncludedTerm());
        assertArrayEquals(image, snapshot.data());
        reopened.close();
    }
}