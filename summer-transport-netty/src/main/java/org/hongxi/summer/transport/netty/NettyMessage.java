package org.hongxi.summer.transport.netty;

/**
 * Created by shenhongxi on 2020/7/25.
 */
public class NettyMessage {
    private long requestId;
    private byte[] data;
    private long startTime;

    public NettyMessage(long requestId, byte[] data) {
        this.requestId = requestId;
        this.data = data;
    }

    public long getRequestId() {
        return requestId;
    }

    public void setRequestId(long requestId) {
        this.requestId = requestId;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }
}
