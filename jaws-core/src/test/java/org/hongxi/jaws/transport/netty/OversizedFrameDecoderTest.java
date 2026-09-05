package org.hongxi.jaws.transport.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression check: an oversized frame must be fully drained (possibly across
 * multiple decode() calls) so that the following frames stay decodable and
 * the connection is not closed. Previously only the currently readable bytes
 * were skipped, which desynchronized the stream once the oversized body
 * arrived in chunks.
 */
class OversizedFrameDecoderTest {

    @Test
    void oversizedFrameBodySplitAcrossChunksMustNotDesynchronizeStream() {
        NettyDecoder decoder = new NettyDecoder(null, 10);
        EmbeddedChannel ch = new EmbeddedChannel(decoder);

        byte[] oversizedBody = new byte[100];
        Arrays.fill(oversizedBody, (byte) 0xAB);

        // Oversized response frame: header + 100-byte body (limit is 10)
        ByteBuf buf = ch.alloc().buffer(16 + oversizedBody.length);
        buf.writeShort(JawsCodec.MAGIC);
        buf.writeByte(1);
        buf.writeByte(JawsCodec.FLAG_RESPONSE);
        buf.writeLong(1L);
        buf.writeInt(oversizedBody.length);
        buf.writeBytes(oversizedBody);

        // Feed header + partial body first, then the remainder,
        // simulating an oversized frame split across chunks
        ch.writeInbound(buf.readRetainedSlice(46));
        ch.writeInbound(buf.readRetainedSlice(buf.readableBytes()));
        buf.release();

        // A valid frame right after must still be decodable
        ByteBuf valid = ch.alloc().buffer(16);
        valid.writeShort(JawsCodec.MAGIC);
        valid.writeByte(1);
        valid.writeByte(JawsCodec.FLAG_RESPONSE);
        valid.writeLong(2L);
        valid.writeInt(0);
        ch.writeInbound(valid);

        Object msg = ch.readInbound();
        assertNotNull(msg, "frame after oversized frame was lost due to stream desynchronization");
        assertTrue(msg instanceof DecodedFrame, "expected DecodedFrame but got: " + msg);
        assertEquals(2L, ((DecodedFrame) msg).requestId());
        ch.finishAndReleaseAll();
    }
}
