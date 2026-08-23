package org.hongxi.jaws.transport.netty;

import io.netty.buffer.ByteBuf;

/**
 * A single decoded protocol frame flowing through the Netty pipeline.
 * <p>
 * Carries the request/response flag and requestId from the protocol header
 * plus the frame body as a zero-copy retained {@link ByteBuf}; ownership is
 * passed from {@link NettyDecoder} to {@link NettyChannelHandler}, which
 * releases the buffer after processing.
 * <p>
 * Created by shenhongxi on 2020/7/25.
 */
public record DecodedFrame(boolean isRequest, long requestId, ByteBuf data) {
}
