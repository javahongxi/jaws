package org.hongxi.jaws.transport.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.hongxi.jaws.transport.Codec;
import org.hongxi.jaws.protocol.jaws.JawsCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression check: data-type flags must not share bit 2 with FLAG_EVENT (0x04),
 * otherwise a data frame would be silently consumed as a heartbeat by NettyDecoder.
 * FLAG_RESPONSE_EXCEPTION used to be 0x05 (bit 2 set) which collided with FLAG_EVENT.
 */
class EventBitCollisionTest {

    @Test
    void exceptionResponseMustNotBeConsumedAsHeartbeat() {
        NettyDecoder decoder = new NettyDecoder(null, null, 0);
        EmbeddedChannel ch = new EmbeddedChannel(decoder);

        // Craft a frame: magic, version=1, flag = FLAG_RESPONSE_EXCEPTION, requestId=42, bodyLen=0
        ByteBuf buf = ch.alloc().buffer(16);
        buf.writeShort(Codec.MAGIC);
        buf.writeByte(1);
        buf.writeByte(JawsCodec.FLAG_RESPONSE_EXCEPTION);
        buf.writeLong(42L);
        buf.writeInt(0);

        ch.writeInbound(buf);

        Object msg = ch.readInbound();
        assertNotNull(msg, "exception response frame was silently consumed as heartbeat (FLAG_EVENT bit collision)");
        assertTrue(msg instanceof DecodedFrame, "expected DecodedFrame but got: " + msg);
        assertEquals(42L, ((DecodedFrame) msg).requestId());
        ch.finishAndReleaseAll();
    }

    @Test
    void heartbeatFrameIsStillConsumed() {
        NettyDecoder decoder = new NettyDecoder(null, null, 0);
        EmbeddedChannel ch = new EmbeddedChannel(decoder);

        ByteBuf buf = ch.alloc().buffer(16);
        buf.writeShort(Codec.MAGIC);
        buf.writeByte(1);
        buf.writeByte(Codec.FLAG_EVENT); // 0x04
        buf.writeLong(0L);
        buf.writeInt(0);

        ch.writeInbound(buf);

        assertNull(ch.readInbound(), "heartbeat frame should be consumed silently");
        assertFalse(ch.finishAndReleaseAll());
    }
}
