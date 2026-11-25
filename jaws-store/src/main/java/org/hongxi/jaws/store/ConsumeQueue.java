package org.hongxi.jaws.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * The logical index of one topic-queue: fixed 20-byte units
 * (commitLogOffset 8B + size 4B + tagsCode 8B reserved), so locating
 * queueOffset N is array-style addressing at N * 20 — no parsing, no search.
 *
 * <p>The CQ is a derived artifact: it has its own relaxed flush ticker, no
 * durability promise, and is rebuilt by replaying the CommitLog on restart.
 * All durability cost is paid once, by the CommitLog alone.
 */
public class ConsumeQueue {

    private static final Logger logger = LoggerFactory.getLogger(ConsumeQueue.class);

    public static final int CQ_STORE_UNIT_SIZE = 20;

    private final String topic;
    private final int queueId;
    private final MappedFileQueue mappedFileQueue;
    /** Reused by the single reput thread — no per-message allocation. */
    private final ByteBuffer unitBuffer = ByteBuffer.allocate(CQ_STORE_UNIT_SIZE);

    public ConsumeQueue(String storePath, String topic, int queueId, int mappedFileSize) {
        this.topic = topic;
        this.queueId = queueId;
        this.mappedFileQueue = new MappedFileQueue(
                storePath + File.separator + "consumequeue" + File.separator + topic + File.separator + queueId,
                mappedFileSize);
    }

    public boolean load() {
        return mappedFileQueue.load();
    }

    /**
     * Write one index unit at queueOffset * 20. Units may arrive out of
     * physical order across queues, but each unit lands at its own logical
     * slot, so the write position only advances by max.
     */
    public boolean putMessagePositionInfo(long commitLogOffset, int size, long queueOffset) {
        long bytePos = queueOffset * CQ_STORE_UNIT_SIZE;
        MappedFile mappedFile = mappedFileQueue.getLastMappedFile(bytePos);
        if (mappedFile == null) {
            logger.error("Failed to create ConsumeQueue file for {}@{}", topic, queueId);
            return false;
        }
        unitBuffer.clear();
        unitBuffer.putLong(commitLogOffset);
        unitBuffer.putInt(size);
        unitBuffer.putLong(0L); // tagsCode reserved, no tag filtering in this engine
        mappedFile.putBytes((int) (bytePos % mappedFile.getFileSize()), unitBuffer.array());
        return true;
    }

    /**
     * A slice of index units starting at startIndex, bounded by both the
     * requested count and the current file boundary. Null when no new unit.
     */
    public ByteBuffer getIndexBuffer(long startIndex, int maxCount) {
        long maxIndex = getMaxOffsetInQueue();
        if (startIndex < 0 || startIndex >= maxIndex) {
            return null;
        }
        long bytePos = startIndex * CQ_STORE_UNIT_SIZE;
        MappedFile mappedFile = mappedFileQueue.findMappedFileByOffset(bytePos);
        if (mappedFile == null) {
            return null;
        }
        int pos = (int) (bytePos % mappedFile.getFileSize());
        int count = (int) Math.min(maxCount, maxIndex - startIndex);
        count = Math.min(count, (mappedFile.getFileSize() - pos) / CQ_STORE_UNIT_SIZE);
        return mappedFile.selectMappedBuffer(pos, count * CQ_STORE_UNIT_SIZE);
    }

    /** Exclusive upper bound of readable queue offsets. */
    public long getMaxOffsetInQueue() {
        return mappedFileQueue.getMaxOffset() / CQ_STORE_UNIT_SIZE;
    }

    /** Files are never deleted in this engine, so the min offset stays 0. */
    public long getMinOffsetInQueue() {
        return 0;
    }

    public void flush() {
        mappedFileQueue.flush();
    }

    public void shutdown() {
        mappedFileQueue.shutdown();
    }

    public String getTopic() {
        return topic;
    }

    public int getQueueId() {
        return queueId;
    }
}