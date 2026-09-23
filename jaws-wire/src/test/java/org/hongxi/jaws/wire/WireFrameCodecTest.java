package org.hongxi.jaws.wire;

import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.hongxi.jaws.wire.health.HealthCheckRequest;
import org.hongxi.jaws.wire.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link WireFrameCodec}: uncompressed and gzip-compressed frame
 * encode/decode round trips, plus the extractPayload path used by the SPI
 * adapter mode.
 *
 * @author shenhongxi
 */
class WireFrameCodecTest {

    private static final HealthCheckRequest REQUEST =
            HealthCheckRequest.newBuilder().setService("demo").build();

    /** The framing API takes negotiated codecs, not encoding names. */
    private static final Codec GZIP = new Codec.Gzip();

    @Test
    void uncompressedRoundTrip() throws Exception {
        ByteBuf frame = WireFrameCodec.encode(REQUEST, ByteBufAllocator.DEFAULT);
        // Frame header: compressed flag 0 + length
        assertEquals(WireConstants.NOT_COMPRESSED, frame.getByte(0));
        HealthCheckRequest decoded = WireFrameCodec.decode(frame, HealthCheckRequest.parser());
        assertEquals(REQUEST, decoded);
        frame.release();
    }

    @Test
    void gzipRoundTrip() throws Exception {
        ByteBuf frame = WireFrameCodec.encode(REQUEST, ByteBufAllocator.DEFAULT,
                GZIP);
        assertEquals(WireConstants.COMPRESSED, frame.getByte(0));
        // Decoding requires the encoding declared in the grpc-encoding header
        HealthCheckRequest decoded = WireFrameCodec.decode(frame, HealthCheckRequest.parser(),
                GZIP);
        assertEquals(REQUEST, decoded);
        frame.release();
    }

    @Test
    void identityCompressorLeavesThePayloadAsIs() throws Exception {
        byte[] rawBytes = REQUEST.toByteArray();
        ByteBuf frame = WireFrameCodec.encodeRawBytes(rawBytes, ByteBufAllocator.DEFAULT,
                Codec.Identity.NONE);
        assertEquals(WireConstants.NOT_COMPRESSED, frame.getByte(0));
        assertArrayEquals(rawBytes,
                WireFrameCodec.extractPayload(frame, Codec.Identity.NONE));
        frame.release();
    }

    @Test
    void emptyPayloadIsNotCompressedEvenWithGzip() {
        // A gzip stream carries roughly 20 bytes of header and footer, so
        // compressing an empty message only inflates the frame; grpc's framer
        // skips it, and the compressed flag has to keep meaning what it says
        ByteBuf frame = WireFrameCodec.encodeRawBytes(new byte[0], ByteBufAllocator.DEFAULT, GZIP);
        assertEquals(WireConstants.NOT_COMPRESSED, frame.getByte(0));
        assertEquals(0, frame.getInt(1));
        frame.release();
    }

    @Test
    void compressedFrameWithoutEncodingFails() {
        ByteBuf frame = WireFrameCodec.encode(REQUEST, ByteBufAllocator.DEFAULT,
                GZIP);
        assertThrows(InvalidProtocolBufferException.class,
                () -> WireFrameCodec.decode(frame, HealthCheckRequest.parser()));
        frame.release();
    }

    @Test
    void extractPayloadRoundTripsRawBytes() throws Exception {
        byte[] rawBytes = REQUEST.toByteArray();
        ByteBuf frame = WireFrameCodec.encodeRawBytes(rawBytes, ByteBufAllocator.DEFAULT,
                GZIP);
        assertEquals(WireConstants.COMPRESSED, frame.getByte(0));
        byte[] extracted = WireFrameCodec.extractPayload(frame, GZIP);
        assertArrayEquals(rawBytes, extracted);
        frame.release();
    }

    @Test
    void corruptedGzipPayloadFailsDecompression() throws Exception {
        // Craft a frame flagged compressed with invalid gzip bytes
        byte[] garbage = {1, 2, 3, 4};
        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gzipped)) {
            out.write(garbage);
        }
        byte[] validGzip = gzipped.toByteArray();
        // Corrupt the payload after the header
        validGzip[validGzip.length - 1] ^= 0xFF;
        ByteBuf frame = ByteBufAllocator.DEFAULT.buffer(5 + validGzip.length);
        frame.writeByte(WireConstants.COMPRESSED).writeInt(validGzip.length).writeBytes(validGzip);

        assertThrows(InvalidProtocolBufferException.class,
                () -> WireFrameCodec.extractPayload(frame, GZIP));
        frame.release();
    }

    @Test
    void tryExtractFrameWaitsForCompletePayload() {
        ByteBuf accumulator = ByteBufAllocator.DEFAULT.buffer();
        // Header only, payload missing
        accumulator.writeByte(WireConstants.NOT_COMPRESSED).writeInt(10);
        assertEquals(null, WireFrameCodec.tryExtractFrame(accumulator));
        // Partial payload
        accumulator.writeBytes(new byte[5]);
        assertEquals(null, WireFrameCodec.tryExtractFrame(accumulator));
        // Complete payload
        accumulator.writeBytes(new byte[5]);
        ByteBuf frame = WireFrameCodec.tryExtractFrame(accumulator);
        assertEquals(15, frame.readableBytes());
        frame.release();
        accumulator.release();
    }

    @Test
    void healthResponseEncodesThroughSpiPath() throws Exception {
        HealthCheckResponse response = HealthCheckResponse.newBuilder()
                .setStatus(HealthCheckResponse.ServingStatus.SERVING).build();
        ByteBuf frame = WireFrameCodec.encode(response, ByteBufAllocator.DEFAULT);
        HealthCheckResponse decoded = WireFrameCodec.decode(frame, HealthCheckResponse.parser());
        assertEquals(HealthCheckResponse.ServingStatus.SERVING, decoded.getStatus());
        frame.release();
    }
}
