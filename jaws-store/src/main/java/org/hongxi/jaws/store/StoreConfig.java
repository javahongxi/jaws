package org.hongxi.jaws.store;

/**
 * Storage engine configuration. Defaults mirror the RocketMQ 5.x conventions
 * studied in doc/mq, with sizes kept configurable so tests can run on
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

    /** Interval between CommitLog/ConsumeQueue cleanup sweeps; 0 disables the
     * cleanup ticker. Mirrors RocketMQ's cleanCommitLog/cleanConsumeQueue run
     * cadence, reduced to one thin sweep thread here. */
    private int cleanupIntervalMillis = 60_000;

    /** Age after which a fully-written, already-dispatched file may be deleted.
     * RocketMQ's fileReservedTime (default 72h); kept as an absolute age so a
     * small demo run can drive it near zero. */
    private long fileRetentionMillis = 72L * 60 * 60 * 1000;

    /** Minimum number of most-recent files that cleanup never touches; must be
     * at least 1 so the active (write-target) file always survives. */
    private int cleanupKeepFiles = 2;

    /** Disk-usage ratio (percent) above which cleanup ignores the retention age
     * and reclaims the oldest files immediately — the disk watermark, RocketMQ's
     * diskMaxUsedSpaceRatio. 0 or 100 disables the pressure trigger. */
    private int diskMaxUsedSpaceRatio = 90;

    /** Interval between consumer-offset snapshots under storePath/config;
     * 0 disables the ticker, leaving only the flush taken at shutdown. Mirrors
     * RocketMQ's flushConsumerOffsetInterval (5s). */
    private int offsetFlushIntervalMillis = 5000;

    /** Attempts reput spends on ONE ConsumeQueue unit before the index is declared
     * broken and the write path fuses. RocketMQ uses 30 inline retries in
     * ConsumeQueue.putMessagePositionInfoWrapper; here the retries are spaced by the
     * reput backoff instead of a fixed 1s, and the unit keeps being retried after the
     * bound so the fuse can lift on its own. Must be positive. */
    private int indexDispatchMaxRetries = 30;

    /** Interval between ConsumerLeaseStore expiry sweeps. Expiry itself is checked
     * lazily on every claim/renew/isHeld, so this only reclaims entries of queues
     * nobody touches and makes a loss show up in the log; 0 leaves the ticker off. */
    private int leaseScanIntervalMillis = 5000;

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

    public int getCleanupIntervalMillis() {
        return cleanupIntervalMillis;
    }

    public void setCleanupIntervalMillis(int cleanupIntervalMillis) {
        this.cleanupIntervalMillis = cleanupIntervalMillis;
    }

    public long getFileRetentionMillis() {
        return fileRetentionMillis;
    }

    public void setFileRetentionMillis(long fileRetentionMillis) {
        this.fileRetentionMillis = fileRetentionMillis;
    }

    public int getCleanupKeepFiles() {
        return cleanupKeepFiles;
    }

    public void setCleanupKeepFiles(int cleanupKeepFiles) {
        this.cleanupKeepFiles = cleanupKeepFiles;
    }

    public int getDiskMaxUsedSpaceRatio() {
        return diskMaxUsedSpaceRatio;
    }

    public void setDiskMaxUsedSpaceRatio(int diskMaxUsedSpaceRatio) {
        this.diskMaxUsedSpaceRatio = diskMaxUsedSpaceRatio;
    }

    public int getOffsetFlushIntervalMillis() {
        return offsetFlushIntervalMillis;
    }

    public void setOffsetFlushIntervalMillis(int offsetFlushIntervalMillis) {
        this.offsetFlushIntervalMillis = offsetFlushIntervalMillis;
    }

    public int getIndexDispatchMaxRetries() {
        return indexDispatchMaxRetries;
    }

    public void setIndexDispatchMaxRetries(int indexDispatchMaxRetries) {
        this.indexDispatchMaxRetries = Math.max(1, indexDispatchMaxRetries);
    }

    public int getLeaseScanIntervalMillis() {
        return leaseScanIntervalMillis;
    }

    public void setLeaseScanIntervalMillis(int leaseScanIntervalMillis) {
        this.leaseScanIntervalMillis = leaseScanIntervalMillis;
    }
}