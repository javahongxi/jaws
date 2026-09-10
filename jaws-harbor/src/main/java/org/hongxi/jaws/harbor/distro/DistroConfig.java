package org.hongxi.jaws.harbor.distro;

/**
 * Configuration for the Distro consensus protocol.
 * <p>
 * Provides tunable parameters for sync delay, verify interval,
 * and load-data retry behavior. Defaults mirror Nacos's constants.
 *
 * @author shenhongxi
 */
public class DistroConfig {

    private long syncDelayMillis = 1000L;
    private long syncTimeoutMillis = 3000L;
    private long syncRetryDelayMillis = 3000L;
    private long verifyIntervalMillis = 5000L;
    private long verifyTimeoutMillis = 3000L;
    private long loadDataRetryDelayMillis = 30000L;

    public long getSyncDelayMillis() {
        return syncDelayMillis;
    }

    public void setSyncDelayMillis(long syncDelayMillis) {
        this.syncDelayMillis = syncDelayMillis;
    }

    public long getSyncTimeoutMillis() {
        return syncTimeoutMillis;
    }

    public void setSyncTimeoutMillis(long syncTimeoutMillis) {
        this.syncTimeoutMillis = syncTimeoutMillis;
    }

    public long getSyncRetryDelayMillis() {
        return syncRetryDelayMillis;
    }

    public void setSyncRetryDelayMillis(long syncRetryDelayMillis) {
        this.syncRetryDelayMillis = syncRetryDelayMillis;
    }

    public long getVerifyIntervalMillis() {
        return verifyIntervalMillis;
    }

    public void setVerifyIntervalMillis(long verifyIntervalMillis) {
        this.verifyIntervalMillis = verifyIntervalMillis;
    }

    public long getVerifyTimeoutMillis() {
        return verifyTimeoutMillis;
    }

    public void setVerifyTimeoutMillis(long verifyTimeoutMillis) {
        this.verifyTimeoutMillis = verifyTimeoutMillis;
    }

    public long getLoadDataRetryDelayMillis() {
        return loadDataRetryDelayMillis;
    }

    public void setLoadDataRetryDelayMillis(long loadDataRetryDelayMillis) {
        this.loadDataRetryDelayMillis = loadDataRetryDelayMillis;
    }
}
