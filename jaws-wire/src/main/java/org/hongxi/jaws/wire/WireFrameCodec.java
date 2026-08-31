package org.hongxi.jaws.wire;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * Codec for the gRPC length-prefixed message frame format.
 * <p>
 * Each gRPC message on the wire is framed as:
 * <pre>
 *   [1 byte compressed-flag] [4 bytes big-endian length] [payload bytes]
 * </pre>
 * The compressed flag is {@code 0} when the payload is sent as-is and
 * {@code 1} when it is compressed with the encoding declared in the call's
 * {@code grpc-encoding} header ({@code identity} or {@code gzip}, see
 * {@link WireCompression}).
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
     * payload when {@code encoding} is a supported compression encoding.
     *
     * @param message  the protobuf message to encode
     * @param alloc    the allocator for the output buffer
     * @param encoding the outbound encoding ({@code null}/identity = uncompressed)
     * @return a new {@link ByteBuf} containing the complete gRPC frame
     */
    public static ByteBuf encode(Message message, ByteBufAllocator alloc, String encoding) {
        return encodeRawBytes(message.toByteArray(), alloc, encoding);
    }

    /**
     * Decode a gRPC frame from the given {@link ByteBuf} into a protobuf {@link Message}.
     * The reader index of {@code frame} must be at the start of the frame header
     * (compressed-flag byte). A compressed payload is decompressed with the
     * call's {@code grpc-encoding} value.
     *
     * @param frame  the buffer positioned at the frame header
     * @param parser the protobuf parser for the expected message type
     * @param <T>    the protobuf message type
     * @return the decoded protobuf message
     * @throws InvalidProtocolBufferException if the payload is not valid protobuf
     * @throws IllegalArgumentException       if the payload is compressed with an
     *                                        unsupported encoding (grpc-status UNIMPLEMENTED)
     */
    public static <T extends Message> T decode(ByteBuf frame, Parser<T> parser)
            throws InvalidProtocolBufferException {
        return decode(frame, parser, null);
    }

    /**
     * Decode a gRPC frame with an explicit inbound encoding.
     *
     * @param frame    the buffer positioned at the frame header
     * @param parser   the protobuf parser for the expected message type
     * @param encoding the encoding declared in the call's grpc-encoding header
     * @param <T>      the protobuf message type
     * @return the decoded protobuf message
     * @throws InvalidProtocolBufferException if the payload is malformed
     * @throws IllegalArgumentException       if the payload is compressed with an
     *                                        unsupported encoding (grpc-status UNIMPLEMENTED)
     */
    public static <T extends Message> T decode(ByteBuf frame, Parser<T> parser, String encoding)
            throws InvalidProtocolBufferException {
        byte[] data = extractPayload(frame, encoding);
        return parser.parseFrom(data);
    }

    /**
     * Encode raw protobuf bytes into a gRPC frame, compressing the payload
     * when {@code encoding} is a supported compression encoding.
     *
     * @param rawBytes the raw protobuf bytes (without gRPC header)
     * @param alloc    the allocator for the output buffer
     * @param encoding the outbound encoding ({@code null}/identity = uncompressed)
     * @return a new {@link ByteBuf} containing the complete gRPC frame
     */
    public static ByteBuf encodeRawBytes(byte[] rawBytes, ByteBufAllocator alloc, String encoding) {
        boolean compressed = encoding != null
                && !WireConstants.ENCODING_IDENTITY.equals(encoding)
                && WireCompression.isSupported(encoding);
        byte[] payload = compressed ? WireCompression.compress(rawBytes, encoding) : rawBytes;
        ByteBuf buf = alloc.buffer(WireConstants.GRPC_HEADER_SIZE + payload.length);
        buf.writeByte(compressed ? WireConstants.COMPRESSED : WireConstants.NOT_COMPRESSED);
        buf.writeInt(payload.length);
        buf.writeBytes(payload);
        return buf;
    }

    /**
     * Extract the payload bytes of one gRPC frame, decompressing when the
     * compressed flag is set. Used by the SPI adapter mode, which carries raw
     * protobuf bytes instead of typed {@link Message} instances.
     *
     * @param frame    the buffer positioned at the frame header
     * @param encoding the encoding declared in the call's grpc-encoding header
     * @return the uncompressed payload bytes
     * @throws InvalidProtocolBufferException if the frame is malformed
     * @throws IllegalArgumentException       if the payload is compressed with an
     *                                        unsupported encoding (grpc-status UNIMPLEMENTED)
     */
    public static byte[] extractPayload(ByteBuf frame, String encoding)
            throws InvalidProtocolBufferException {
        byte compressedFlag = frame.readByte();
        int length = frame.readInt();
        byte[] data = new byte[length];
        frame.readBytes(data);
        if (compressedFlag == WireConstants.NOT_COMPRESSED) {
            return data;
        }
        if (encoding == null || WireConstants.ENCODING_IDENTITY.equals(encoding)) {
            throw new InvalidProtocolBufferException(
                    "Compressed gRPC message but no grpc-encoding declared");
        }
        // Unsupported encoding propagates as IllegalArgumentException so the
        // server can report UNIMPLEMENTED per the gRPC spec
        try {
            return WireCompression.decompress(data, encoding);
        } catch (IllegalStateException e) {
            throw new InvalidProtocolBufferException(
                    "Failed to decompress gRPC message with encoding " + encoding
                            + ": " + e.getMessage());
        }
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
