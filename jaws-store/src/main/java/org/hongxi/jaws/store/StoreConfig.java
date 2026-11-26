package org.hongxi.jaws.store;

/**
 * Storage engine configuration. Defaults mirror the RocketMQ 5.x conventions
 * studied in doc/rocketmq, with sizes kept configurable so tests can run on
 * small files.
 */
public class StoreConfig {

    /** Root directory holding commitlog/ and consumequeue/. */
    private String storePath = System.getProperty("user.home") + "/jaws-store";

    /** Stable broker identity, persisted under storePath on first start and
     * verified on every restart. Consumer offsets are keyed by it, so a
     * rename against existing data is refused. Multi-instance deployments
     * must set distinct names. */
    private String brokerName = "broker";

    /** CommitLog file size, 1G by default (RocketMQ mappedFileSizeCommitLog). */
    private int mappedFileSizeCommitLog = 1024 * 1024 * 1024;

    /** ConsumeQueue file size, 300000 units x 20B by default. */
    private int mappedFileSizeConsumeQueue = 300000 * ConsumeQueue.CQ_STORE_UNIT_SIZE;

    /** Upper bound of a single encoded message. */
    private int maxMessageSize = 4 * 1024 * 1024;

    /** true = SYNC_FLUSH (group commit), false = ASYNC_FLUSH (periodic ticker). */
    private boolean syncFlush = false;

    /** Async flush ticker interval in millis (RocketMQ flushIntervalCommitLog). */
    private int flushIntervalCommitLog = 500;

    /** Max wait for a group-commit fsync before reporting FLUSH_TIMEOUT. */
    private int syncFlushTimeout = 5000;

    /** ConsumeQueue flush ticker interval in millis; CQ is rebuildable, so it
     * never participates in group commit or any durability promise. */
    private int flushIntervalConsumeQueue = 1000;

    /** Fallback scan interval of the long-polling hold service. */
    private int holdScanIntervalMillis = 5000;

    public String getStorePath() {
        return storePath;
    }

    public void setStorePath(String storePath) {
        this.storePath = storePath;
    }

    public String getBrokerName() {
        return brokerName;
    }

    public void setBrokerName(String brokerName) {
        this.brokerName = brokerName;
    }

    public int getMappedFileSizeCommitLog() {
        return mappedFileSizeCommitLog;
    }

    public void setMappedFileSizeCommitLog(int mappedFileSizeCommitLog) {
        this.mappedFileSizeCommitLog = mappedFileSizeCommitLog;
    }

    public int getMappedFileSizeConsumeQueue() {
        return mappedFileSizeConsumeQueue;
    }

    public void setMappedFileSizeConsumeQueue(int mappedFileSizeConsumeQueue) {
        this.mappedFileSizeConsumeQueue = mappedFileSizeConsumeQueue;
    }

    public int getMaxMessageSize() {
        return maxMessageSize;
    }

    public void setMaxMessageSize(int maxMessageSize) {
        this.maxMessageSize = maxMessageSize;
    }

    public boolean isSyncFlush() {
        return syncFlush;
    }

    public void setSyncFlush(boolean syncFlush) {
        this.syncFlush = syncFlush;
    }

    public int getFlushIntervalCommitLog() {
        return flushIntervalCommitLog;
    }

    public void setFlushIntervalCommitLog(int flushIntervalCommitLog) {
        this.flushIntervalCommitLog = flushIntervalCommitLog;
    }

    public int getSyncFlushTimeout() {
        return syncFlushTimeout;
    }

    public void setSyncFlushTimeout(int syncFlushTimeout) {
        this.syncFlushTimeout = syncFlushTimeout;
    }

    public int getFlushIntervalConsumeQueue() {
        return flushIntervalConsumeQueue;
    }

    public void setFlushIntervalConsumeQueue(int flushIntervalConsumeQueue) {
        this.flushIntervalConsumeQueue = flushIntervalConsumeQueue;
    }

    public int getHoldScanIntervalMillis() {
        return holdScanIntervalMillis;
    }

    public void setHoldScanIntervalMillis(int holdScanIntervalMillis) {
        this.holdScanIntervalMillis = holdScanIntervalMillis;
    }
}