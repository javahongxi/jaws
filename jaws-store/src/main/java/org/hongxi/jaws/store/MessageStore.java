package org.hongxi.jaws.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * The storage facade wiring the four moving parts together:
 * CommitLog (write) → ReputMessageService (async dispatch) → ConsumeQueue
 * (read index) → PullRequestHoldService (long polling).
 *
 * <p>Restart recovery follows the "original is authoritative, derivatives
 * are recomputable" principle: the CommitLog write position is recovered by
 * scanning its last file, then ALL ConsumeQueues are rebuilt by a synchronous
 * replay. No checkpoint bookkeeping is needed at this scale.
 */
public class MessageStore implements ReputMessageService.DispatchListener {

    private static final Logger logger = LoggerFactory.getLogger(MessageStore.class);

    private final StoreConfig config;
    private final CommitLog commitLog;
    private final ConcurrentMap<String, ConsumeQueue> consumeQueueTable = new ConcurrentHashMap<>();
    private final ReputMessageService reputMessageService;
    private final PullRequestHoldService pullRequestHoldService;
    private volatile boolean stopped;
    private Thread cqFlushThread;

    public MessageStore(StoreConfig config) {
        this.config = config;
        this.commitLog = new CommitLog(config);
        this.reputMessageService = new ReputMessageService(commitLog, this);
        this.pullRequestHoldService = new PullRequestHoldService(this, config.getHoldScanIntervalMillis());
    }

    public void start() throws IOException {
        checkBrokerIdentity();
        if (!commitLog.load()) {
            throw new IOException("Failed to load commitlog files under " + config.getStorePath());
        }
        long maxOffset = commitLog.recover();
        rebuildConsumeQueues(maxOffset);
        // Pre-allocate (and warm) the first commitlog file before accepting
        // traffic, so the page-fault cost of a fresh 1G mapping is paid at
        // startup instead of inside the first put's global lock.
        commitLog.preallocateFirstFile();
        reputMessageService.setReputFromOffset(maxOffset);
        reputMessageService.start();
        commitLog.start();
        pullRequestHoldService.start();
        startCqFlushTicker();
        logger.info("MessageStore started, storePath={}, syncFlush={}", config.getStorePath(), config.isSyncFlush());
    }

    /**
     * Bind the data directory to a stable broker identity. Consumer offsets
     * are keyed by brokerName, so a renamed broker would orphan them; refuse
     * the mismatch instead of silently serving old data under a new name.
     */
    private void checkBrokerIdentity() throws IOException {
        Path identityFile = new File(config.getStorePath() + File.separator + "broker.identity").toPath();
        if (Files.exists(identityFile)) {
            String persisted = Files.readString(identityFile, StandardCharsets.UTF_8).trim();
            if (!persisted.equals(config.getBrokerName())) {
                throw new IOException("Broker identity mismatch under " + config.getStorePath()
                        + ": persisted=" + persisted + ", configured=" + config.getBrokerName());
            }
            return;
        }
        Files.createDirectories(identityFile.getParent());
        Files.writeString(identityFile, config.getBrokerName(), StandardCharsets.UTF_8);
    }

    /** Drop all derived indexes and replay the CommitLog synchronously. */
    private void rebuildConsumeQueues(long maxOffset) throws IOException {
        Path cqRoot = new File(config.getStorePath() + File.separator + "consumequeue").toPath();
        if (Files.exists(cqRoot)) {
            try (Stream<Path> paths = Files.walk(cqRoot)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        long offset = 0;
        long replayed = 0;
        while (offset < maxOffset) {
            DispatchEntry entry = commitLog.readEntry(offset);
            if (entry == null) {
                // jump over the unused tail of a rolled file
                long next = commitLog.skipFileTail(offset);
                if (next > offset && next <= maxOffset) {
                    offset = next;
                    continue;
                }
                break;
            }
            findOrCreateConsumeQueue(entry.getTopic(), entry.getQueueId())
                    .putMessagePositionInfo(entry.getCommitLogOffset(), entry.getSize(), entry.getQueueOffset());
            offset += entry.getSize();
            replayed++;
        }
        logger.info("Replayed {} messages to rebuild ConsumeQueues", replayed);
    }

    private void startCqFlushTicker() {
        cqFlushThread = new Thread(() -> {
            while (!stopped) {
                try {
                    Thread.sleep(config.getFlushIntervalConsumeQueue());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                for (ConsumeQueue consumeQueue : consumeQueueTable.values()) {
                    consumeQueue.flush();
                }
            }
        }, "StoreCqFlush");
        cqFlushThread.setDaemon(true);
        cqFlushThread.start();
    }

    /** Reput callback: index dispatch FIRST, arrival notification SECOND. */
    @Override
    public void onDispatch(DispatchEntry entry) {
        ConsumeQueue consumeQueue = findOrCreateConsumeQueue(entry.getTopic(), entry.getQueueId());
        consumeQueue.putMessagePositionInfo(entry.getCommitLogOffset(), entry.getSize(), entry.getQueueOffset());
        pullRequestHoldService.notifyMessageArrive(entry.getTopic(), entry.getQueueId(), entry.getQueueOffset() + 1);
    }

    private ConsumeQueue findOrCreateConsumeQueue(String topic, int queueId) {
        return consumeQueueTable.computeIfAbsent(CommitLog.topicQueueKey(topic, queueId),
                k -> new ConsumeQueue(config.getStorePath(), topic, queueId, config.getMappedFileSizeConsumeQueue()));
    }

    /**
     * Append + durability barrier. Async flush completes as soon as the
     * memcpy lands in page cache; sync flush joins the group-commit batch.
     */
    public CompletableFuture<PutResult> putMessageAsync(StoreMessage message) {
        if (commitLog.isOSPageCacheBusy()) {
            return CompletableFuture.completedFuture(new PutResult(PutStatus.OS_PAGE_CACHE_BUSY));
        }
        PutResult result = commitLog.putMessage(message);
        if (result.isOk() && config.isSyncFlush()) {
            int size = MessageCodec.calTotalSize(message.getBody().length,
                    message.getTopic().getBytes(StandardCharsets.UTF_8).length);
            return commitLog.submitFlush(result.getCommitLogOffset() + size)
                    .thenApply(v -> result)
                    .exceptionally(ex -> new PutResult(PutStatus.FLUSH_TIMEOUT));
        }
        return CompletableFuture.completedFuture(result);
    }

    /** Synchronous pull core: CQ addressing + CommitLog slice decode. */
    public PullResult getMessage(String topic, int queueId, long offset, int maxCount) {
        ConsumeQueue consumeQueue = consumeQueueTable.get(CommitLog.topicQueueKey(topic, queueId));
        if (consumeQueue == null) {
            return new PullResult(PullStatus.NO_NEW_MSG, offset, 0, 0);
        }
        long minOffset = consumeQueue.getMinOffsetInQueue();
        long maxOffset = consumeQueue.getMaxOffsetInQueue();
        if (offset < minOffset || offset > maxOffset) {
            return new PullResult(PullStatus.OFFSET_ILLEGAL,
                    Math.max(minOffset, Math.min(offset, maxOffset)), minOffset, maxOffset);
        }
        if (offset == maxOffset) {
            return new PullResult(PullStatus.NO_NEW_MSG, offset, minOffset, maxOffset);
        }
        PullResult result = new PullResult(PullStatus.FOUND, offset, minOffset, maxOffset);
        ByteBuffer indexBuffer = consumeQueue.getIndexBuffer(offset, maxCount);
        long next = offset;
        while (indexBuffer != null && indexBuffer.hasRemaining()) {
            long commitLogOffset = indexBuffer.getLong();
            int size = indexBuffer.getInt();
            indexBuffer.getLong(); // tagsCode reserved
            MessageView view = commitLog.getMessage(commitLogOffset, size);
            if (view == null) {
                break;
            }
            result.getMessages().add(view);
            next++;
        }
        result.setNextBeginOffset(next);
        return result;
    }

    /**
     * Pull with long polling: an empty result is suspended and completed by
     * message arrival or the hold deadline, instead of returning immediately.
     */
    public CompletableFuture<PullResult> getMessageAsync(String topic, int queueId, long offset,
                                                         int maxCount, long suspendTimeoutMillis) {
        PullResult result = getMessage(topic, queueId, offset, maxCount);
        if (result.getStatus() != PullStatus.NO_NEW_MSG || suspendTimeoutMillis <= 0) {
            return CompletableFuture.completedFuture(result);
        }
        PullRequestHoldService.HoldRequest request =
                new PullRequestHoldService.HoldRequest(topic, queueId, offset, maxCount, suspendTimeoutMillis);
        pullRequestHoldService.suspend(request);
        // re-check after registration: a message may have arrived between
        // the empty read and the suspend, whose notification we just missed
        if (getMaxOffsetInQueue(topic, queueId) > offset) {
            pullRequestHoldService.wakeup(request);
        }
        return request.future;
    }

    public long getMaxOffsetInQueue(String topic, int queueId) {
        ConsumeQueue consumeQueue = consumeQueueTable.get(CommitLog.topicQueueKey(topic, queueId));
        return consumeQueue == null ? 0 : consumeQueue.getMaxOffsetInQueue();
    }

    public void shutdown() {
        stopped = true;
        if (cqFlushThread != null) {
            cqFlushThread.interrupt();
        }
        reputMessageService.shutdown();
        pullRequestHoldService.shutdown();
        for (ConsumeQueue consumeQueue : consumeQueueTable.values()) {
            consumeQueue.shutdown();
        }
        commitLog.shutdown();
        logger.info("MessageStore stopped");
    }

    public StoreConfig getConfig() {
        return config;
    }
}