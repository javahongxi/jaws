package org.hongxi.jaws.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The single physical log all topics append to — sequential writes regardless
 * of topic/queue count, the root of RocketMQ's write-scaling story.
 *
 * <p>Two-layer locking keeps the global critical section down to one memcpy:
 * <ul>
 *   <li>per topic-queue lock: assign queueOffset + encode (CPU work, parallel
 *       across queues)</li>
 *   <li>global putMessageLock: patch physicalOffset/storeTimestamp + memcpy
 *       into the mmap slice + file roll when needed</li>
 * </ul>
 *
 * <p>Encoding reuses a thread-local direct buffer, so the data path never
 * touches the JVM heap: direct buffer → page cache.
 *
 * <p>Simplification vs RocketMQ: on a rare append failure after offset
 * assignment the slot offset is rolled back best-effort (CAS); a lost race
 * leaves a hole in the queue and is logged instead of repaired.
 */
public class CommitLog {

    private static final Logger logger = LoggerFactory.getLogger(CommitLog.class);

    /** Held-lock duration beyond which the page cache is considered stalled. */
    private static final long PAGE_CACHE_BUSY_MILLIS = 1000;

    private final StoreConfig config;
    private final MappedFileQueue mappedFileQueue;
    private final FlushService flushService;

    private final ReentrantLock putMessageLock = new ReentrantLock();
    private final ConcurrentMap<String, TopicQueueSlot> queueSlots = new ConcurrentHashMap<>();
    private final ThreadLocal<ByteBuffer> encoderBuffer;

    /** Acquire time of the global lock; 0 when nobody holds it. Doubles as
     * the page-cache stall probe, exactly like RocketMQ's beginTimeInLock. */
    private volatile long beginTimeInLock;

    private static class TopicQueueSlot {
        final ReentrantLock lock = new ReentrantLock();
        final AtomicLong nextQueueOffset = new AtomicLong(0);
    }

    public CommitLog(StoreConfig config) {
        this.config = config;
        this.mappedFileQueue = new MappedFileQueue(
                config.getStorePath() + File.separator + "commitlog", config.getMappedFileSizeCommitLog());
        this.encoderBuffer = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(config.getMaxMessageSize()));
        this.flushService = config.isSyncFlush()
                ? new GroupCommitService(this, config.getSyncFlushTimeout())
                : new AsyncFlushService(this, config.getFlushIntervalCommitLog());
    }

    public boolean load() {
        return mappedFileQueue.load();
    }

    /** Ensure a writable last file exists before traffic arrives; a fresh
     * file is warmed at creation, so this moves that cost to startup. */
    public void preallocateFirstFile() {
        mappedFileQueue.getLastMappedFile(0);
    }

    /**
     * Recover the write position by scanning the last file for valid records,
     * and rebuild per-queue next offsets. Earlier files are assumed intact.
     *
     * @return the global offset just after the last valid record
     */
    public long recover() {
        MappedFile last = mappedFileQueue.getLastMappedFile();
        if (last == null) {
            return 0;
        }
        int pos = 0;
        int fileSize = last.getFileSize();
        while (pos + MessageCodec.HEADER_SIZE <= fileSize) {
            int size = last.getInt(pos);
            int magic = last.getInt(pos + 4);
            if (size <= 0 || magic != MessageCodec.MESSAGE_MAGIC || pos + size > fileSize) {
                break;
            }
            long queueOffset = last.getLong(pos + 16);
            int queueId = last.getInt(pos + 8);
            int bodyLen = last.getInt(pos + 40);
            int topicLen = last.getByte(pos + 44 + bodyLen) & 0xFF;
            ByteBuffer topicSlice = last.selectMappedBuffer(pos + 44 + bodyLen + 1, topicLen);
            byte[] topicBytes = new byte[topicLen];
            topicSlice.get(topicBytes);
            String topic = new String(topicBytes, StandardCharsets.UTF_8);
            TopicQueueSlot slot = queueSlots.computeIfAbsent(topicQueueKey(topic, queueId), k -> new TopicQueueSlot());
            slot.nextQueueOffset.accumulateAndGet(queueOffset + 1, Math::max);
            pos += size;
        }
        last.setWrotePosition(pos);
        long maxOffset = last.getFileFromOffset() + pos;
        logger.info("CommitLog recovered, maxOffset={}", maxOffset);
        return maxOffset;
    }

    /**
     * Append one message. Never waits for disk: under sync flush the caller
     * additionally awaits {@link #submitFlush(long)}.
     */
    public PutResult putMessage(StoreMessage message) {
        byte[] topicBytes = message.getTopic().getBytes(StandardCharsets.UTF_8);
        int totalSize = MessageCodec.calTotalSize(message.getBody().length, topicBytes.length);
        if (totalSize > config.getMaxMessageSize() || topicBytes.length > 255) {
            return new PutResult(PutStatus.MESSAGE_SIZE_EXCEEDED);
        }

        /* Layer 1: per topic-queue lock — offset assignment + encode */
        TopicQueueSlot slot = queueSlots.computeIfAbsent(
                topicQueueKey(message.getTopic(), message.getQueueId()), k -> new TopicQueueSlot());
        long queueOffset;
        ByteBuffer buffer;
        slot.lock.lock();
        try {
            queueOffset = slot.nextQueueOffset.getAndIncrement();
            buffer = encoderBuffer.get();
            buffer.clear();
            MessageCodec.encode(message, queueOffset, buffer);
            buffer.flip();
        } finally {
            slot.lock.unlock();
        }

        /* Layer 2: global lock — patch position fields + memcpy into mmap */
        putMessageLock.lock();
        try {
            beginTimeInLock = System.currentTimeMillis();
            MappedFile lastFile = mappedFileQueue.getLastMappedFile(0);
            if (lastFile != null && lastFile.getRemaining() < totalSize) {
                lastFile = mappedFileQueue.rollNextFile();
            }
            if (lastFile == null || lastFile.getRemaining() < totalSize) {
                rollbackOffset(slot, queueOffset);
                return new PutResult(PutStatus.CREATE_FILE_FAILED);
            }
            long physicalOffset = lastFile.getFileFromOffset() + lastFile.getWrotePosition();
            buffer.putLong(MessageCodec.PHYSICAL_OFFSET_POS, physicalOffset);
            buffer.putLong(MessageCodec.STORE_TIMESTAMP_POS, System.currentTimeMillis());
            lastFile.appendMessage(buffer);
            return new PutResult(PutStatus.PUT_OK, physicalOffset, queueOffset);
        } finally {
            beginTimeInLock = 0;
            putMessageLock.unlock();
        }
    }

    private void rollbackOffset(TopicQueueSlot slot, long queueOffset) {
        if (!slot.nextQueueOffset.compareAndSet(queueOffset + 1, queueOffset)) {
            logger.warn("Queue offset {} wasted after append failure, a hole is left in the queue", queueOffset);
        }
    }

    /**
     * Durability barrier for an append ending at nextOffset. Async flush
     * returns immediately (PUT_OK = in page cache); sync flush joins the
     * group-commit batch and completes after one shared fsync.
     */
    public CompletableFuture<Void> submitFlush(long nextOffset) {
        return flushService.submitFlush(nextOffset);
    }

    /** Read one record header + topic for the reput dispatcher; null at the
     * end of valid data. */
    public DispatchEntry readEntry(long offset) {
        MappedFile mappedFile = mappedFileQueue.findMappedFileByOffset(offset);
        if (mappedFile == null) {
            return null;
        }
        int pos = (int) (offset % mappedFile.getFileSize());
        if (pos + MessageCodec.HEADER_SIZE > mappedFile.getWrotePosition()) {
            return null;
        }
        int size = mappedFile.getInt(pos);
        int magic = mappedFile.getInt(pos + 4);
        if (size <= 0 || magic != MessageCodec.MESSAGE_MAGIC
                || pos + size > mappedFile.getWrotePosition()) {
            return null;
        }
        int queueId = mappedFile.getInt(pos + 8);
        long queueOffset = mappedFile.getLong(pos + 16);
        long storeTimestamp = mappedFile.getLong(pos + 32);
        int bodyLen = mappedFile.getInt(pos + 40);
        int topicLen = mappedFile.getByte(pos + 44 + bodyLen) & 0xFF;
        ByteBuffer topicSlice = mappedFile.selectMappedBuffer(pos + 44 + bodyLen + 1, topicLen);
        byte[] topicBytes = new byte[topicLen];
        topicSlice.get(topicBytes);
        String topic = new String(topicBytes, StandardCharsets.UTF_8);
        return new DispatchEntry(offset, size, topic, queueId, queueOffset, storeTimestamp);
    }

    /** Decode a full message view from a known physical offset and size. */
    public MessageView getMessage(long offset, int size) {
        MappedFile mappedFile = mappedFileQueue.findMappedFileByOffset(offset);
        if (mappedFile == null) {
            return null;
        }
        int pos = (int) (offset % mappedFile.getFileSize());
        ByteBuffer slice = mappedFile.selectMappedBuffer(pos, size);
        return MessageCodec.decode(slice);
    }

    public long flush() {
        return mappedFileQueue.flush();
    }

    /**
     * Rolling leaves an unused tail in a full file (the next message did not
     * fit), so physical offsets are NOT contiguous across files. Sequential
     * readers (reput, replay) call this to jump over such a gap when a record
     * read fails before the global max offset.
     */
    public long skipFileTail(long offset) {
        int fileSize = config.getMappedFileSizeCommitLog();
        return (offset / fileSize + 1) * fileSize;
    }

    public long getMaxOffset() {
        return mappedFileQueue.getMaxOffset();
    }

    /** Oldest CommitLog byte still on disk; advances as cleanup reclaims files. */
    public long getMinOffset() {
        return mappedFileQueue.getMinOffset();
    }

    /** Durability frontier: the offset up to which data has been fsynced. */
    public long getFlushedOffset() {
        return mappedFileQueue.getFlushedOffset();
    }

    /**
     * Reclaim expired CommitLog files, protecting any file the reput dispatcher
     * has not yet read ({@code protectFromOffset} = current dispatch position).
     *
     * @return the number of files deleted
     */
    public int deleteExpiredFiles(long retentionMillis, int keepMinFiles, boolean forceByDiskPressure,
                                  long protectFromOffset) {
        return mappedFileQueue.deleteExpiredFiles(retentionMillis, keepMinFiles, forceByDiskPressure,
                protectFromOffset);
    }

    /** Page-cache stall probe: the memcpy-only critical section should take
     * microseconds, so a held lock beyond one second means the kernel is
     * stuck on page fault or writeback. */
    public boolean isOSPageCacheBusy() {
        long begin = beginTimeInLock;
        return begin > 0 && System.currentTimeMillis() - begin > PAGE_CACHE_BUSY_MILLIS;
    }

    public void start() {
        flushService.start();
    }

    public void shutdown() {
        flushService.shutdown();
        mappedFileQueue.shutdown();
    }

    public static String topicQueueKey(String topic, int queueId) {
        return topic + "@" + queueId;
    }
}