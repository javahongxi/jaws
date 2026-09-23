package org.hongxi.jaws.wire;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Codec for the gRPC length-prefixed message frame format.
 * <p>
 * Each gRPC message on the wire is framed as:
 * <pre>
 *   [1 byte compressed-flag] [4 bytes big-endian length] [payload bytes]
 * </pre>
 * The compressed flag is {@code 0} when the payload is sent as-is and
 * {@code 1} when it was compressed by the {@link Compressor} named in the
 * call's {@code grpc-encoding} header.
 * <p>
 * This codec takes negotiated <em>instances</em>, not encoding names: looking
 * a name up in a {@link CompressorRegistry} or {@link DecompressorRegistry} is
 * the negotiation step, done once per call, and by the time a message is
 * framed the answer is already settled. Passing a name down would re-open the
 * "who decides what is supported" question on every message.
 * <p>
 * This codec does not use or depend on grpc-java; it operates directly on
 * {@link ByteBuf} and protobuf {@link Message} instances.
 *
 * @author shenhongxi
 */
public final class WireFrameCodec {

    private WireFrameCodec() {
    }

    /**
     * Encode a protobuf {@link Message} into an uncompressed gRPC frame:
     * {@code [compressed-flag(1)][length(4)][protobuf bytes]}.
     *
     * @param message the protobuf message to encode
     * @param alloc    the allocator for the output buffer
     * @return a new {@link ByteBuf} containing the complete gRPC frame
     */
    public static ByteBuf encode(Message message, ByteBufAllocator alloc) {
        return encode(message, alloc, null);
    }

    /**
     * Encode a protobuf {@link Message} into a gRPC frame, compressing the
     * payload when a compressor was negotiated for this call.
     *
     * @param message    the protobuf message to encode
     * @param alloc      the allocator for the output buffer
     * @param compressor the negotiated compressor; {@code null} or
     *                   {@link Codec.Identity#NONE} sends the payload as-is
     * @return a new {@link ByteBuf} containing the complete gRPC frame
     */
    public static ByteBuf encode(Message message, ByteBufAllocator alloc, Compressor compressor) {
        return encodeRawBytes(message.toByteArray(), alloc, compressor);
    }

    /**
     * Decode a gRPC frame from the given {@link ByteBuf} into a protobuf {@link Message}.
     * The reader index of {@code frame} must be at the start of the frame header
     * (compressed-flag byte). A compressed payload is rejected, because no
     * encoding was negotiated for this call.
     *
     * @param frame  the buffer positioned at the frame header
     * @param parser the protobuf parser for the expected message type
     * @param <T>    the protobuf message type
     * @return the decoded protobuf message
     * @throws InvalidProtocolBufferException if the payload is not valid protobuf,
     *                                        or is compressed while nothing was
     *                                        negotiated
     */
    public static <T extends Message> T decode(ByteBuf frame, Parser<T> parser)
            throws InvalidProtocolBufferException {
        return decode(frame, parser, null);
    }

    /**
     * Decode a gRPC frame with the decompressor negotiated for this call.
     *
     * @param frame        the buffer positioned at the frame header
     * @param parser       the protobuf parser for the expected message type
     * @param decompressor the negotiated decompressor; {@code null} or
     *                     {@link Codec.Identity#NONE} accepts only uncompressed
     *                     payloads
     * @param <T>          the protobuf message type
     * @return the decoded protobuf message
     * @throws InvalidProtocolBufferException if the payload is malformed, or is
     *                                        compressed while nothing was negotiated
     */
    public static <T extends Message> T decode(ByteBuf frame, Parser<T> parser,
                                               Decompressor decompressor)
            throws InvalidProtocolBufferException {
        byte[] data = extractPayload(frame, decompressor);
        return parser.parseFrom(data);
    }

    /**
     * Encode raw protobuf bytes into a gRPC frame, compressing the payload when
     * a compressor was negotiated.
     *
     * @param rawBytes   the raw protobuf bytes (without gRPC header)
     * @param alloc      the allocator for the output buffer
     * @param compressor the negotiated compressor; {@code null} or
     *                   {@link Codec.Identity#NONE} sends the payload as-is
     * @return a new {@link ByteBuf} containing the complete gRPC frame
     * @throws IllegalStateException if compression fails
     */
    public static ByteBuf encodeRawBytes(byte[] rawBytes, ByteBufAllocator alloc,
                                        Compressor compressor) {
        // A compressor named by reference inequality with the identity
        // sentinel, as in grpc-java; and, also as there, an empty message is
        // not worth compressing — a gzip stream has a fixed ~20-byte overhead,
        // so "compressing" an empty payload only grows the frame.
        boolean compressed = compressor != null
                && compressor != Codec.Identity.NONE
                && rawBytes.length > 0;
        byte[] payload = rawBytes;
        if (compressed) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(rawBytes.length / 2 + 16);
            try (OutputStream compressedOut = compressor.compress(out)) {
                compressedOut.write(rawBytes);
            } catch (IOException e) {
                throw new IllegalStateException(
                        compressor.getMessageEncoding() + " compression failed", e);
            }
            payload = out.toByteArray();
        }
        ByteBuf buf = alloc.buffer(WireConstants.GRPC_HEADER_SIZE + payload.length);
        buf.writeByte(compressed ? WireConstants.COMPRESSED : WireConstants.NOT_COMPRESSED);
        buf.writeInt(payload.length);
        buf.writeBytes(payload);
        return buf;
    }

    /**
     * Extract the payload bytes of one gRPC frame, decompressing when the
     * compressed flag is set. Used by the Provider pipeline mode, which carries raw
     * protobuf bytes instead of typed {@link Message} instances.
     *
     * @param frame        the buffer positioned at the frame header
     * @param decompressor the negotiated decompressor; {@code null} or
     *                     {@link Codec.Identity#NONE} rejects a compressed payload
     * @return the uncompressed payload bytes
     * @throws InvalidProtocolBufferException if the frame is malformed, the
     *                                        payload is compressed while nothing was
     *                                        negotiated, or it does not decode
     */
    public static byte[] extractPayload(ByteBuf frame, Decompressor decompressor)
            throws InvalidProtocolBufferException {
        byte compressedFlag = frame.readByte();
        int length = frame.readInt();
        byte[] data = new byte[length];
        frame.readBytes(data);
        if (compressedFlag == WireConstants.NOT_COMPRESSED) {
            return data;
        }
        if (decompressor == null || decompressor == Codec.Identity.NONE) {
            throw new InvalidProtocolBufferException(
                    "Compressed gRPC message but no grpc-encoding declared");
        }
        try (InputStream decompressed = decompressor.decompress(new ByteArrayInputStream(data))) {
            return decompressed.readAllBytes();
        } catch (IOException e) {
            throw new InvalidProtocolBufferException(
                    "Failed to decompress gRPC message with encoding "
                            + decompressor.getMessageEncoding() + ": " + e.getMessage());
        }
    }

    /**
     * Size of the message payload carried by one complete gRPC frame, i.e. the
     * length-prefixed bytes excluding the 5-byte frame header. This is the
     * {@code wireSize} a {@link StreamTracer} reports: the compressed length
     * when the frame is compressed.
     *
     * @param frame a buffer positioned at the frame header and holding exactly
     *              one complete frame, as returned by {@link #tryExtractFrame}
     * @return the payload size in bytes
     */
    public static int payloadSize(ByteBuf frame) {
        return frame.readableBytes() - WireConstants.GRPC_HEADER_SIZE;
    }

    /**
     * Try to extract one complete gRPC frame from the accumulator buffer.
     * <p>
     * If the accumulator contains at least a full header (5 bytes) and the
     * indicated payload, a retained slice of the frame is returned and the
     * accumulator's reader index is advanced past it. Otherwise {@code null}
     * is returned and the accumulator is left unchanged, indicating that more
     * data needs to be accumulated before a frame can be extracted.
     *
     * @param accumulator the buffer accumulating incoming DATA frame bytes
     * @return a retained slice containing one complete gRPC frame, or {@code null}
     */
    public static ByteBuf tryExtractFrame(ByteBuf accumulator) {
        if (accumulator.readableBytes() < WireConstants.GRPC_HEADER_SIZE) {
            return null; // header incomplete
        }
        accumulator.markReaderIndex();
        accumulator.skipBytes(1); // skip compressed flag
        int length = accumulator.readInt();
        accumulator.resetReaderIndex();

        if (accumulator.readableBytes() < WireConstants.GRPC_HEADER_SIZE + length) {
            return null; // payload incomplete
        }
        return accumulator.readRetainedSlice(WireConstants.GRPC_HEADER_SIZE + length);
    }
}
