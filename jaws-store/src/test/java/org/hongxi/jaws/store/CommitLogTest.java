package org.hongxi.jaws.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for CommitLog: append/read roundtrip, file rolling, recovery
 * and per-queue offset assignment.
 */
class CommitLogTest {

    @TempDir
    Path tempDir;

    private StoreConfig config() {
        StoreConfig config = new StoreConfig();
        config.setStorePath(tempDir.toString());
        config.setMappedFileSizeCommitLog(1024);
        config.setMaxMessageSize(4096);
        return config;
    }

    @Test
    void appendAndReadRoundTrip() {
        CommitLog commitLog = new CommitLog(config());
        PutResult result = commitLog.putMessage(StoreMessage.of("topic-a", 0, "hello"));
        assertEquals(PutStatus.PUT_OK, result.getStatus());
        assertEquals(0, result.getQueueOffset());

        DispatchEntry entry = commitLog.readEntry(result.getCommitLogOffset());
        assertNotNull(entry);
        MessageView view = commitLog.getMessage(result.getCommitLogOffset(), entry.getSize());
        assertEquals("topic-a", view.getTopic());
        assertEquals("hello", view.bodyAsString());
        assertEquals(0, view.getQueueOffset());
        assertEquals(result.getCommitLogOffset(), view.getCommitLogOffset());
        assertTrue(view.getStoreTimestamp() > 0);
        commitLog.shutdown();
    }

    @Test
    void fileRollKeepsContentAndQueueOffsets() {
        CommitLog commitLog = new CommitLog(config());
        long[] offsets = new long[30];
        for (int i = 0; i < 30; i++) {
            PutResult result = commitLog.putMessage(StoreMessage.of("topic-a", 0, "message-" + i));
            assertEquals(PutStatus.PUT_OK, result.getStatus());
            assertEquals(i, result.getQueueOffset());
            offsets[i] = result.getCommitLogOffset();
        }
        assertTrue(commitLog.getMaxOffset() > 1024, "30 messages should roll past the first 1K file");
        for (int i = 0; i < 30; i++) {
            DispatchEntry entry = commitLog.readEntry(offsets[i]);
            assertNotNull(entry, "entry " + i + " must survive file rolling");
            MessageView view = commitLog.getMessage(offsets[i], entry.getSize());
            assertEquals("message-" + i, view.bodyAsString());
            assertEquals(i, view.getQueueOffset());
        }
        commitLog.shutdown();
    }

    @Test
    void recoverContinuesQueueOffsets() {
        StoreConfig config = config();
        CommitLog first = new CommitLog(config);
        long maxOffset = 0;
        for (int i = 0; i < 5; i++) {
            PutResult result = first.putMessage(StoreMessage.of("topic-a", 0, "m-" + i));
            maxOffset = result.getCommitLogOffset() + first.readEntry(result.getCommitLogOffset()).getSize();
        }
        first.shutdown();

        CommitLog second = new CommitLog(config);
        assertTrue(second.load());
        assertEquals(maxOffset, second.recover());
        PutResult result = second.putMessage(StoreMessage.of("topic-a", 0, "after-restart"));
        assertEquals(PutStatus.PUT_OK, result.getStatus());
        assertEquals(5, result.getQueueOffset(), "queue offset must continue after recovery");
        second.shutdown();
    }

    @Test
    void oversizedMessageRejected() {
        CommitLog commitLog = new CommitLog(config());
        byte[] body = new byte[5000];
        PutResult result = commitLog.putMessage(new StoreMessage("topic-a", 0, body));
        assertEquals(PutStatus.MESSAGE_SIZE_EXCEEDED, result.getStatus());
        commitLog.shutdown();
    }

    @Test
    void queueOffsetsAreIndependentPerQueue() {
        CommitLog commitLog = new CommitLog(config());
        for (int i = 0; i < 3; i++) {
            assertEquals(i, commitLog.putMessage(StoreMessage.of("topic-a", 0, "q0-" + i)).getQueueOffset());
        }
        for (int i = 0; i < 2; i++) {
            assertEquals(i, commitLog.putMessage(StoreMessage.of("topic-a", 1, "q1-" + i)).getQueueOffset());
        }
        commitLog.shutdown();
    }
}