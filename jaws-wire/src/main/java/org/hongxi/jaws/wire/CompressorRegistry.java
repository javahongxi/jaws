package org.hongxi.jaws.wire;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The compressors this side is willing to send, looked up by the name used in
 * the {@code grpc-encoding} header.
 * <p>
 * The default instance carries {@code gzip} and {@code identity}, so
 * configuring a call to either never needs registration. Registering a codec
 * is how a third encoding becomes selectable: one
 * {@code register(new ZstdCompressor())} call makes it usable by name, with
 * no change to framing, negotiation or the header writers.
 * <p>
 * Registration is mutable and shared, exactly as in grpc-java: the registry is
 * intended to be configured once during start-up. {@code getDefaultInstance()}
 * is process-wide, so a test that registers into it leaks into its neighbours —
 * prefer {@link #newEmptyInstance()} and hand that to the channel or server.
 * <p>
 * Note the deliberate asymmetry with {@link DecompressorRegistry}, inherited
 * from grpc-java: compressors are registered into a mutable table, whereas
 * decompressors are added to an immutable one that returns a new instance,
 * because the advertised set must be recomputed as a whole.
 * <p>
 * Mirrors {@code io.grpc.CompressorRegistry}.
 *
 * @author shenhongxi
 */
public final class CompressorRegistry {

    private static final CompressorRegistry DEFAULT_INSTANCE = new CompressorRegistry(
            new Codec.Gzip(), Codec.Identity.NONE);

    /**
     * @return the instance gRPC uses when none is configured; contains gzip and
     *         identity
     */
    public static CompressorRegistry getDefaultInstance() {
        return DEFAULT_INSTANCE;
    }

    /**
     * @return a new instance with no registered compressors
     */
    public static CompressorRegistry newEmptyInstance() {
        return new CompressorRegistry();
    }

    private final ConcurrentMap<String, Compressor> compressors;

    CompressorRegistry(Compressor... cs) {
        compressors = new ConcurrentHashMap<>();
        for (Compressor c : cs) {
            compressors.put(c.getMessageEncoding(), c);
        }
    }

    /**
     * Finds the compressor for an encoding name.
     *
     * @param compressorName the name from {@code grpc-encoding}, or as given to
     *                       a call's compression option
     * @return the compressor, or {@code null} when nothing is registered under
     *         that name — which is what distinguishes "not supported" from
     *         "supported and identity"
     */
    public Compressor lookupCompressor(String compressorName) {
        return compressors.get(compressorName);
    }

    /**
     * Registers a compressor, replacing any registration under the same name.
     *
     * @param compressor the compressor to add
     * @throws IllegalArgumentException if the encoding name contains a comma,
     *                                  which would corrupt the comma-joined
     *                                  {@code grpc-accept-encoding} header
     */
    public void register(Compressor compressor) {
        String encoding = compressor.getMessageEncoding();
        if (encoding.contains(",")) {
            throw new IllegalArgumentException(
                    "Comma is currently not allowed in message encoding");
        }
        compressors.put(encoding, compressor);
    }
}
