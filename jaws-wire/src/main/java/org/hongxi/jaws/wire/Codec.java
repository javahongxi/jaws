package org.hongxi.jaws.wire;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The {@link Compressor} / {@link Decompressor} pair for one message encoding.
 * <p>
 * jaws-wire ships two codecs and both are dependency-free: {@link Gzip} on
 * {@code java.util.zip}, and {@link Identity}. Anything else — zstd, snappy —
 * is an implementation registered at runtime, not built in here.
 * <p>
 * Mirrors {@code io.grpc.Codec}.
 *
 * @author shenhongxi
 */
public interface Codec extends Compressor, Decompressor {

    /**
     * gzip as defined by RFC 1952, which is what the gRPC wire format means
     * by {@code grpc-encoding: gzip}.
     */
    final class Gzip implements Codec {

        @Override
        public String getMessageEncoding() {
            return WireConstants.ENCODING_GZIP;
        }

        @Override
        public OutputStream compress(OutputStream os) throws IOException {
            return new GZIPOutputStream(os);
        }

        @Override
        public InputStream decompress(InputStream is) throws IOException {
            return new GZIPInputStream(is);
        }
    }

    /**
     * No compression.
     */
    final class Identity implements Codec {

        /**
         * Sentinel meaning "do not compress". Compare it by reference, as
         * grpc-java does: framing decides whether to run a codec at all by
         * asking whether the negotiated compressor <em>is</em> this object, so
         * an equal-but-separate identity codec would still be a no-op yet
         * defeat the one check that keeps the frame flag honest.
         */
        public static final Codec NONE = new Identity();

        private Identity() {
        }

        @Override
        public String getMessageEncoding() {
            return WireConstants.ENCODING_IDENTITY;
        }

        @Override
        public OutputStream compress(OutputStream os) {
            return os;
        }

        @Override
        public InputStream decompress(InputStream is) {
            return is;
        }
    }
}
