package org.hongxi.jaws.wire;

import java.io.IOException;
import java.io.InputStream;

/**
 * Decompresses the bytes of one gRPC message.
 * <p>
 * Implementations are registered in a {@link DecompressorRegistry}; the
 * encoding named by the peer's {@code grpc-encoding} header is looked up once
 * per call and then applied to every message carrying the compressed frame
 * flag.
 * <p>
 * Mirrors {@code io.grpc.Decompressor}.
 *
 * @author shenhongxi
 * @see Codec
 * @see DecompressorRegistry
 */
public interface Decompressor {

    /**
     * @return the name this decompressor is registered under, e.g.
     *         {@code gzip}; must not contain a comma, because the
     *         {@code grpc-accept-encoding} header is a comma-joined list
     */
    String getMessageEncoding();

    /**
     * Wraps the input stream so that reading the returned stream yields the
     * decompressed bytes.
     *
     * @param is the source stream holding compressed bytes
     * @return a stream that decompresses {@code is}; the caller closes it
     * @throws IOException if the payload cannot be decompressed
     */
    InputStream decompress(InputStream is) throws IOException;
}
