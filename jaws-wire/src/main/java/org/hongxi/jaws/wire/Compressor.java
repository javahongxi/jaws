package org.hongxi.jaws.wire;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Compresses the bytes of one gRPC message.
 * <p>
 * Implementations are registered in a {@link CompressorRegistry} and selected
 * by name through the {@code grpc-encoding} header, so one instance serves
 * every message of a call. Instances must therefore be stateless apart from
 * configuration: per-message state belongs to the streams returned from
 * {@link #compress(OutputStream)}, not to the compressor.
 * <p>
 * Mirrors {@code io.grpc.Compressor}.
 *
 * @author shenhongxi
 * @see Codec
 * @see CompressorRegistry
 */
public interface Compressor {

    /**
     * @return the name this compressor is advertised and negotiated under,
     *         e.g. {@code gzip}; must not contain a comma, because the
     *         {@code grpc-accept-encoding} header is a comma-joined list
     */
    String getMessageEncoding();

    /**
     * Wraps the output stream so that bytes written to the returned stream are
     * compressed and written to {@code os}.
     *
     * @param os the destination stream
     * @return a stream that compresses into {@code os}; the caller closes it,
     *         which closes {@code os} as well
     * @throws IOException if compression cannot be started or written
     */
    OutputStream compress(OutputStream os) throws IOException;
}
