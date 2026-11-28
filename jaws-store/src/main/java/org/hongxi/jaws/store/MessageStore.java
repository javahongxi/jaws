package org.hongxi.jaws.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
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
 *
 * <p>The same derivability backs the index fuse: because a ConsumeQueue can always
 * be rebuilt, an index that stops advancing is treated as a reason to refuse writes
 * rather than to abandon the tail — see {@link #isWriteable()}.
 */
public class MessageStore implements ReputMessageService.DispatchListener {

    private static final Logger log = LoggerFactory.getLogger(MessageStore.class);

    private final StoreConfig config;
    private final CommitLog commitLog;
    private final ConcurrentMap<String, ConsumeQueue> consumeQueueTable = new ConcurrentHashMap<>();
    private final ReputMessageService reputMessageService;
    private final PullRequestHoldService pullRequestHoldService;
    /**
     * Pulls served so far. The one counter left on the broker, and not an
     * observability surface: tests use it to see that a request actually arrived
     * (short-polling backoff spacing, pump attachment after discovery).
     */
    private final AtomicLong pullCount = new AtomicLong();
    /**
     * The write path's view of index health, counterpart of RocketMQ's RunningFlags
     * WRITE_LOGICS_QUEUE_ERROR_BIT: false while the fuse is set — by the dispatcher,
     * once it exceeded {@code indexDispatchMaxRetries} attempts on one unit — and true
     * again once a retried unit lands. While false, appends are refused outright: a log
     * whose index cannot advance must not keep growing, and a refusal consumes no queue
     * offset, so healing leaves no gap behind.
     */
    private volatile boolean indexHealthy = true;
    private volatile boolean stopped;
    private Thread cqFlushThread;
    private Thread cleanupThread;

    public MessageStore(StoreConfig config) {
        this.config = config;
        this.commitLog = new CommitLog(config);
        this.reputMessageService = new ReputMessageService(commitLog, this, config.getIndexDispatchMaxRetries());
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
        startCleanupTicker();
        log.info("MessageStore started, storePath={}, syncFlush={}", config.getStorePath(), config.isSyncFlush());
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
        log.info("Replayed {} messages to rebuild ConsumeQueues", replayed);
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

    /**
     * Periodic reclamation sweep. Disabled when the interval is non-positive.
     * Runs the same {@link #cleanupFiles()} an operator can trigger manually,
     * on {@code cleanupIntervalMillis}; exceptions are swallowed so one bad pass
     * never kills the schedule.
     */
    private void startCleanupTicker() {
        int interval = config.getCleanupIntervalMillis();
        if (interval <= 0) {
            return;
        }
        cleanupThread = new Thread(() -> {
            while (!stopped) {
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                try {
                    cleanupFiles();
                } catch (Exception e) {
                    log.warn("Cleanup sweep failed, retry next interval", e);
                }
            }
        }, "StoreCleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    /**
     * One reclamation pass. CommitLog first: files fully dispatched by reput and
     * past retention (or forced by disk pressure) are dropped, which advances the
     * CommitLog min offset. Then each ConsumeQueue sheds the index files whose
     * referenced CommitLog offsets all fall below that new min — the index never
     * outlives its data, and the queue min offset moves forward so a lagging
     * consumer reads OFFSET_ILLEGAL instead of a deleted slot.
     *
     * @return the number of CommitLog files deleted in this pass
     */
    public int cleanupFiles() {
        // Never reclaim a file the dispatcher has not read yet.
        long protectFromOffset = reputMessageService.getReputFromOffset();
        boolean diskPressure = isDiskPressure();
        int deleted = commitLog.deleteExpiredFiles(config.getFileRetentionMillis(),
                config.getCleanupKeepFiles(), diskPressure, protectFromOffset);
        if (deleted > 0 || diskPressure) {
            long commitLogMinOffset = commitLog.getMinOffset();
            for (ConsumeQueue consumeQueue : consumeQueueTable.values()) {
                consumeQueue.deleteExpiredFiles(commitLogMinOffset, config.getCleanupKeepFiles());
            }
        }
        return deleted;
    }

    /**
     * Disk watermark: true when the store volume's used space has reached
     * {@code diskMaxUsedSpaceRatio}, the signal to ignore retention age and
     * reclaim aggressively (RocketMQ's diskMaxUsedSpaceRatio trigger). Disabled
     * when the ratio is unset or 100.
     */
    private boolean isDiskPressure() {
        int ratio = config.getDiskMaxUsedSpaceRatio();
        if (ratio <= 0 || ratio >= 100) {
            return false;
        }
        File dir = new File(config.getStorePath());
        long total = dir.getTotalSpace();
        long usable = dir.getUsableSpace();
        if (total <= 0 || usable < 0) {
            return false;
        }
        return (total - usable) * 100L / total >= ratio;
    }

    /**
     * Reput callback: index dispatch FIRST, arrival notification SECOND. A unit
     * that did not land is reported back so the dispatcher holds its position;
     * notifying anyway would wake a pull onto an index that is not there yet.
     */
    @Override
    public boolean onDispatch(DispatchEntry entry) {
        ConsumeQueue consumeQueue = findOrCreateConsumeQueue(entry.getTopic(), entry.getQueueId());
        if (!consumeQueue.putMessagePositionInfo(entry.getCommitLogOffset(), entry.getSize(), entry.getQueueOffset())) {
            return false;
        }
        pullRequestHoldService.notifyMessageArrive(entry.getTopic(), entry.getQueueId(), entry.getQueueOffset() + 1);
        return true;
    }

    private ConsumeQueue findOrCreateConsumeQueue(String topic, int queueId) {
        return consumeQueueTable.computeIfAbsent(CommitLog.topicQueueKey(topic, queueId),
                k -> new ConsumeQueue(config.getStorePath(), topic, queueId, config.getMappedFileSizeConsumeQueue()));
    }

    /**
     * Reput health edge: the dispatcher reports the break and its healing, this
     * holds the resulting gate. Only the write path reads it.
     */
    @Override
    public void onIndexHealthChanged(boolean healthy) {
        indexHealthy = healthy;
    }

    /**
     * Write gate, the equivalent of RocketMQ's broker testing
     * {@code RunningFlags.isWriteable()} in HookUtils.beforePutMessage. False while
     * index dispatch is fused; reads and cleanup are unaffected.
     */
    public boolean isWriteable() {
        return indexHealthy;
    }

    /**
     * Append + durability barrier. Async flush completes as soon as the
     * memcpy lands in page cache; sync flush joins the group-commit batch.
     *
     * <p>Refused outright, before anything is appended, while the index is fused.
     */
    public CompletableFuture<PutResult> putMessageAsync(StoreMessage message) {
        if (!indexHealthy) {
            return CompletableFuture.completedFuture(new PutResult(PutStatus.SERVICE_NOT_AVAILABLE));
        }
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

    /**
     * Append a batch of messages in one call. Each record is still written
     * individually into the CommitLog (batching is not group commit — see the
     * design doc), but the batch shares ONE durability barrier: under sync flush
     * a single fsync to the highest end offset satisfies every message in it,
     * because physical offsets are globally monotonic. The value over N separate
     * puts is a single RPC frame and a single fsync, not fewer appends.
     *
     * <p>Results are returned in input order; a per-message failure does not
     * abort the batch. Under page-cache stall the whole batch is fast-failed with
     * {@link PutStatus#OS_PAGE_CACHE_BUSY}, matching the single-put contract; while
     * the index is fused the whole batch is refused with
     * {@link PutStatus#SERVICE_NOT_AVAILABLE}, likewise.
     */
    public CompletableFuture<List<PutResult>> putMessagesAsync(List<StoreMessage> messages) {
        List<PutResult> results = new ArrayList<>(messages.size());
        if (messages.isEmpty()) {
            return CompletableFuture.completedFuture(results);
        }
        if (!indexHealthy) {
            for (int i = 0; i < messages.size(); i++) {
                results.add(new PutResult(PutStatus.SERVICE_NOT_AVAILABLE));
            }
            return CompletableFuture.completedFuture(results);
        }
        if (commitLog.isOSPageCacheBusy()) {
            for (int i = 0; i < messages.size(); i++) {
                results.add(new PutResult(PutStatus.OS_PAGE_CACHE_BUSY));
            }
            return CompletableFuture.completedFuture(results);
        }
        long maxEndOffset = -1;
        for (StoreMessage message : messages) {
            PutResult result = commitLog.putMessage(message);
            results.add(result);
            if (result.isOk()) {
                int size = MessageCodec.calTotalSize(message.getBody().length,
                        message.getTopic().getBytes(StandardCharsets.UTF_8).length);
                maxEndOffset = Math.max(maxEndOffset, result.getCommitLogOffset() + size);
            }
        }
        if (config.isSyncFlush() && maxEndOffset >= 0) {
            long flushOffset = maxEndOffset;
            return commitLog.submitFlush(flushOffset)
                    .thenApply(v -> results)
                    .exceptionally(ex -> {
                        List<PutResult> timedOut = new ArrayList<>(results.size());
                        for (int i = 0; i < results.size(); i++) {
                            timedOut.add(results.get(i).isOk()
                                    ? new PutResult(PutStatus.FLUSH_TIMEOUT) : results.get(i));
                        }
                        return timedOut;
                    });
        }
        return CompletableFuture.completedFuture(results);
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
        pullCount.incrementAndGet();
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

    /** Number of pull requests served so far (test probe for "did the request land"). */
    public long getPullCount() {
        return pullCount.get();
    }

    /** Live write frontier of the log, for lag and roll-size assertions. */
    public long getCommitLogMaxOffset() {
        return commitLog.getMaxOffset();
    }

    public void shutdown() {
        stopped = true;
        if (cqFlushThread != null) {
            cqFlushThread.interrupt();
        }
        if (cleanupThread != null) {
            cleanupThread.interrupt();
        }
        reputMessageService.shutdown();
        pullRequestHoldService.shutdown();
        for (ConsumeQueue consumeQueue : consumeQueueTable.values()) {
            consumeQueue.shutdown();
        }
        commitLog.shutdown();
        log.info("MessageStore stopped");
    }

    public StoreConfig getConfig() {
        return config;
    }
}