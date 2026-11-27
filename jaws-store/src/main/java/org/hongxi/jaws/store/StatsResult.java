package org.hongxi.jaws.store;

import java.io.Serializable;

/**
 * A point-in-time snapshot of the broker's operating counters, returned by the
 * admin {@link StoreAdminService#statsAsync()} surface. It is a plain wire POJO
 * like the module's other results (PutResult, PullResult, AdminViewResult), so
 * an operator or a stress harness can read throughput and backlog numbers over
 * the same jaws port the data path uses.
 *
 * <p>Every field is either a monotonic counter captured without resetting or a
 * gauge derived from live positions at read time; none of them require walking
 * the log, so taking a snapshot is cheap and never blocks the data path.
 */
public class StatsResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Stable broker identity this snapshot belongs to (see BrokerInfo). */
    private String brokerName;

    /** Total appends that landed in the log (PUT_OK), lifetime. */
    private long putTotal;

    /** Total appends rejected before write or at create, lifetime. */
    private long putRejectedTotal;

    /** Times a write was fast-failed by the page-cache stall probe, lifetime. */
    private long pageCacheBusyTotal;

    /** Total pull requests served (including held ones), lifetime. */
    private long pullTotal;

    /** Global offset just past the last byte ever written to the CommitLog. */
    private long commitLogMaxOffset;

    /** Durability frontier: the offset up to which data is fsynced. */
    private long commitLogFlushedOffset;

    /** Bytes written but not yet dispatched to ConsumeQueue (reput lag). */
    private long dispatchBehindBytes;

    /** Number of topic-queues currently indexed by this broker. */
    private int consumeQueueCount;

    public StatsResult() {
    }

    public String getBrokerName() {
        return brokerName;
    }

    public void setBrokerName(String brokerName) {
        this.brokerName = brokerName;
    }

    public long getPutTotal() {
        return putTotal;
    }

    public void setPutTotal(long putTotal) {
        this.putTotal = putTotal;
    }

    public long getPutRejectedTotal() {
        return putRejectedTotal;
    }

    public void setPutRejectedTotal(long putRejectedTotal) {
        this.putRejectedTotal = putRejectedTotal;
    }

    public long getPageCacheBusyTotal() {
        return pageCacheBusyTotal;
    }

    public void setPageCacheBusyTotal(long pageCacheBusyTotal) {
        this.pageCacheBusyTotal = pageCacheBusyTotal;
    }

    public long getPullTotal() {
        return pullTotal;
    }

    public void setPullTotal(long pullTotal) {
        this.pullTotal = pullTotal;
    }

    public long getCommitLogMaxOffset() {
        return commitLogMaxOffset;
    }

    public void setCommitLogMaxOffset(long commitLogMaxOffset) {
        this.commitLogMaxOffset = commitLogMaxOffset;
    }

    public long getCommitLogFlushedOffset() {
        return commitLogFlushedOffset;
    }

    public void setCommitLogFlushedOffset(long commitLogFlushedOffset) {
        this.commitLogFlushedOffset = commitLogFlushedOffset;
    }

    public long getDispatchBehindBytes() {
        return dispatchBehindBytes;
    }

    public void setDispatchBehindBytes(long dispatchBehindBytes) {
        this.dispatchBehindBytes = dispatchBehindBytes;
    }

    public int getConsumeQueueCount() {
        return consumeQueueCount;
    }

    public void setConsumeQueueCount(int consumeQueueCount) {
        this.consumeQueueCount = consumeQueueCount;
    }
}
