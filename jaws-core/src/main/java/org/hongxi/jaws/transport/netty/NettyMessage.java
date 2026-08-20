package org.hongxi.jaws.transport.netty;

import io.netty.buffer.ByteBuf;

/**
 * Created by shenhongxi on 2020/7/25.
 */
public record NettyMessage(boolean isRequest, long requestId, ByteBuf data) {
}
