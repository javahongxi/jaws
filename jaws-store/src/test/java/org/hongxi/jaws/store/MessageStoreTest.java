package org.hongxi.jaws.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for MessageStore: put/pull roundtrip across file boundaries,
 * group-commit sync flush, restart replay and long-polling semantics.
 */
class MessageStoreTest {

    @TempDir
    Path tempDir;

    private StoreConfig config() {
        StoreConfig config = new StoreConfig();
        config.setStorePath(tempDir.toString());
        config.setMappedFileSizeCommitLog(1024);
        config.setMappedFileSizeConsumeQueue(10 * ConsumeQueue.CQ_STORE_UNIT_SIZE);
        config.setMaxMessageSize(4096);
        config.setHoldScanIntervalMillis(50);
        return config;
    }

    private MessageStore started(StoreConfig config) throws IOException {
        MessageStore store = new MessageStore(config);
        store.start();
        return store;
    }

    private void awaitIndex(MessageStore store, String topic, int queueId, long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (store.getMaxOffsetInQueue(topic, queueId) < expected) {
            assertTrue(System.currentTimeMillis() < deadline, "reput did not catch up in time");
            Thread.sleep(10);
        }
    }

    private List<String> pullAll(MessageStore store, String topic, int queueId, int expected) {
        List<String> bodies = new ArrayList<>();
        long offset = 0;
        while (bodies.size() < expected) {
            PullResult result = store.getMessage(topic, queueId, offset, 32);
            assertEquals(PullStatus.FOUND, result.getStatus());
            result.getMessages().forEach(m -> bodies.add(m.bodyAsString()));
            offset = result.getNextBeginOffset();
        }
        return bodies;
    }

    @Test
    void putPullRoundTripAcrossFileBoundaries() throws Exception {
        MessageStore store = started(config());
        for (int i = 0; i < 25; i++) {
            PutResult result = store.putMessageAsync(StoreMessage.of("topic-a", 0, "m-" + i)).get(2, TimeUnit.SECONDS);
            assertEquals(PutStatus.PUT_OK, result.getStatus());
        }
        awaitIndex(store, "topic-a", 0, 25);
        List<String> bodies = pullAll(store, "topic-a", 0, 25);
        for (int i = 0; i < 25; i++) {
            assertEquals("m-" + i, bodies.get(i));
        }
        store.shutdown();
    }

    @Test
    void syncFlushGroupCommitSatisfiesWholeBatch() throws Exception {
        StoreConfig config = config();
        config.setSyncFlush(true);
        MessageStore store = started(config);
        List<CompletableFuture<PutResult>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(store.putMessageAsync(StoreMessage.of("topic-a", 0, "sync-" + i)));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
        for (CompletableFuture<PutResult> future : futures) {
            assertEquals(PutStatus.PUT_OK, future.get().getStatus());
        }
        awaitIndex(store, "topic-a", 0, 20);
        assertEquals(20, pullAll(store, "topic-a", 0, 20).size());
        store.shutdown();
    }

    @Test
    void restartRebuildsIndexesByReplay() throws Exception {
        StoreConfig config = config();
        MessageStore first = started(config);
        for (int i = 0; i < 10; i++) {
            first.putMessageAsync(StoreMessage.of("topic-a", 0, "old-" + i)).get(2, TimeUnit.SECONDS);
        }
        awaitIndex(first, "topic-a", 0, 10);
        first.shutdown();

        MessageStore second = started(config);
        assertEquals(10, second.getMaxOffsetInQueue("topic-a", 0), "CQ must be rebuilt from CommitLog replay");
        List<String> bodies = pullAll(second, "topic-a", 0, 10);
        assertEquals("old-0", bodies.get(0));
        PutResult result = second.putMessageAsync(StoreMessage.of("topic-a", 0, "new")).get(2, TimeUnit.SECONDS);
        assertEquals(10, result.getQueueOffset());
        second.shutdown();
    }

    @Test
    void longPollingWokenByArrival() throws Exception {
        MessageStore store = started(config());
        CompletableFuture<PullResult> future = store.getMessageAsync("topic-a", 0, 0, 32, 5000);
        Thread.sleep(50);
        assertFalse(future.isDone(), "empty pull must be held, not returned");
        store.putMessageAsync(StoreMessage.of("topic-a", 0, "wake-up")).get(2, TimeUnit.SECONDS);
        PullResult result = future.get(2, TimeUnit.SECONDS);
        assertEquals(PullStatus.FOUND, result.getStatus());
        assertEquals("wake-up", result.getMessages().get(0).bodyAsString());
        store.shutdown();
    }

    @Test
    void longPollingTimesOutWithNoNewMsg() throws Exception {
        MessageStore store = started(config());
        long begin = System.currentTimeMillis();
        PullResult result = store.getMessageAsync("topic-a", 0, 0, 32, 200).get(2, TimeUnit.SECONDS);
        assertEquals(PullStatus.NO_NEW_MSG, result.getStatus());
        assertTrue(System.currentTimeMillis() - begin >= 150, "hold must last until the deadline");
        store.shutdown();
    }

    @Test
    void illegalOffsetIsReported() throws Exception {
        MessageStore store = started(config());
        store.putMessageAsync(StoreMessage.of("topic-a", 0, "only")).get(2, TimeUnit.SECONDS);
        awaitIndex(store, "topic-a", 0, 1);
        PullResult result = store.getMessage("topic-a", 0, 5, 32);
        assertEquals(PullStatus.OFFSET_ILLEGAL, result.getStatus());
        assertEquals(1, result.getNextBeginOffset());
        store.shutdown();
    }

    @Test
    void batchPutRoundTripKeepsOrder() throws Exception {
        MessageStore store = started(config());
        List<StoreMessage> batch = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            batch.add(StoreMessage.of("topic-a", 0, "b-" + i));
        }
        List<PutResult> results = store.putMessagesAsync(batch).get(2, TimeUnit.SECONDS);
        assertEquals(5, results.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(PutStatus.PUT_OK, results.get(i).getStatus());
            assertEquals(i, results.get(i).getQueueOffset());
        }
        awaitIndex(store, "topic-a", 0, 5);
        List<String> bodies = pullAll(store, "topic-a", 0, 5);
        for (int i = 0; i < 5; i++) {
            assertEquals("b-" + i, bodies.get(i));
        }
        store.shutdown();
    }

    @Test
    void cleanupReclaimsFilesAndAdvancesMinOffsets() throws Exception {
        StoreConfig config = config();
        config.setCleanupKeepFiles(1);      // retain only the active file
        config.setFileRetentionMillis(0);   // treat every file as past retention
        config.setCleanupIntervalMillis(0); // no ticker; drive cleanupFiles() directly
        MessageStore store = started(config);
        // 60 records over 1024B files (~55B each) roll several files past the head
        int total = 60;
        for (int i = 0; i < total; i++) {
            store.putMessageAsync(StoreMessage.of("topic-a", 0, "c-" + i)).get(2, TimeUnit.SECONDS);
        }
        // reput must reach maxOffset before head files are dispatchable-and-deletable
        awaitIndex(store, "topic-a", 0, total);
        assertTrue(store.getCommitLogMaxOffset() > 2L * 1024,
                "expected multiple rolled commitlog files");

        int deleted = store.cleanupFiles();
        assertTrue(deleted > 0, "cleanup should reclaim expired commitlog files");

        // data below the reclaimed boundary is gone: a pull from offset 0 now reads
        // past the advanced CQ min, so it reports OFFSET_ILLEGAL rather than a hole
        PullResult pull = store.getMessage("topic-a", 0, 0, 32);
        assertEquals(PullStatus.OFFSET_ILLEGAL, pull.getStatus());
        assertTrue(pull.getMinOffset() > 0, "CQ min offset should advance after reclamation");

        // the broker stays writable after reclamation
        PutResult again = store.putMessageAsync(StoreMessage.of("topic-a", 0, "live")).get(2, TimeUnit.SECONDS);
        assertEquals(PutStatus.PUT_OK, again.getStatus());
        store.shutdown();
    }
}