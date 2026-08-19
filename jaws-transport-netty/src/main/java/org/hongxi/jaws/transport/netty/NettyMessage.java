package org.hongxi.jaws.transport.netty;

/**
 * Created by shenhongxi on 2020/7/25.
 */
public record NettyMessage(boolean isRequest, long requestId, byte[] data) {
}
