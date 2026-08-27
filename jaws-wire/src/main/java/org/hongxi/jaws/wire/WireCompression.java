package org.hongxi.jaws.wire;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Message compression support for the gRPC wire format ({@code grpc-encoding}).
 * <p>
 * Supported encodings: {@code identity} (no compression) and {@code gzip}.
 * The compressed flag in the 5-byte gRPC frame header indicates whether the
 * payload was compressed with the encoding declared in the {@code grpc-encoding}
 * header of the same call.
 *
 * @author shenhongxi
 */
public final class WireCompression {

    private WireCompression() {
    }

    /**
     * @param encoding the encoding name from the grpc-encoding header
     * @return true if the encoding is supported for compress/decompress
     */
    public static boolean isSupported(String encoding) {
        return encoding == null
                || WireConstants.ENCODING_IDENTITY.equals(encoding)
                || WireConstants.ENCODING_GZIP.equals(encoding);
    }

    /**
     * Compress the payload with the given encoding.
     *
     * @param data     the uncompressed payload
     * @param encoding the target encoding ({@code identity} returns a copy)
     * @return the compressed payload
     * @throws IllegalArgumentException if the encoding is unsupported
     * @throws IllegalStateException    if compression fails
     */
    public static byte[] compress(byte[] data, String encoding) {
        if (encoding == null || WireConstants.ENCODING_IDENTITY.equals(encoding)) {
            return data;
        }
        if (WireConstants.ENCODING_GZIP.equals(encoding)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 2 + 16);
            try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
                gzip.write(data);
            } catch (IOException e) {
                throw new IllegalStateException("gzip compression failed", e);
            }
            return out.toByteArray();
        }
        throw new IllegalArgumentException("Unsupported gRPC encoding: " + encoding);
    }

    /**
     * Decompress the payload with the given encoding.
     *
     * @param data     the compressed payload
     * @param encoding the encoding declared in the grpc-encoding header
     * @return the uncompressed payload
     * @throws IllegalArgumentException if the encoding is unsupported
     * @throws IllegalStateException    if the payload is not valid for the encoding
     */
    public static byte[] decompress(byte[] data, String encoding) {
        if (encoding == null || WireConstants.ENCODING_IDENTITY.equals(encoding)) {
            return data;
        }
        if (WireConstants.ENCODING_GZIP.equals(encoding)) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
                return gzip.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException("gzip decompression failed", e);
            }
        }
        throw new IllegalArgumentException("Unsupported gRPC encoding: " + encoding);
    }
}
