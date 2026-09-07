package org.hongxi.jaws.transport.netty;

import io.netty.buffer.ByteBuf;

/**
 * A single decoded protocol frame flowing through the Netty pipeline.
 * <p>
 * Carries the pre-extracted header fields (flag, requestId) plus the body as
 * a zero-copy retained {@link ByteBuf}; ownership is passed from
 * {@link NettyDecoder} to {@link NettyChannelHandler}, which releases the
 * buffer after processing.
 * <p>
 * The header (16 bytes) is parsed exactly once by {@link NettyDecoder}; the
 * pre-extracted fields are forwarded so that {@link JawsCodec#decodeBody}
 * can skip the header and decode only the body payload.
 * <p>
 * Created by shenhongxi on 2020/7/25.
 */
public record DecodedFrame(boolean isRequest, long requestId, byte flag, ByteBuf body) {
}
