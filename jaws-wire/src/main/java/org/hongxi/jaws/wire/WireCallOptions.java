package org.hongxi.jaws.wire;

/**
 * Per-invocation options for a wire (gRPC) client call, layered on top of the
 * static URL / dynamic-configuration defaults. This is the per-call slice of
 * grpc-java's {@code CallOptions}: a caller can pin <em>this</em> RPC's deadline
 * and request compressor without changing the channel-wide configuration.
 * <p>
 * Immutable. A {@code null} field means "inherit the configured default", so
 * {@link #DEFAULT} reproduces exactly the pre-existing behaviour.
 *
 * @author shenhongxi
 */
public final class WireCallOptions {

    /** Empty options: every field inherits the client's configured default. */
    public static final WireCallOptions DEFAULT = new WireCallOptions(null, null);

    /** Per-call deadline in milliseconds; {@code null} → resolve from config. */
    private final Integer deadlineMs;
    /** Per-call request compressor ("identity" or "gzip"); {@code null} → client setting. */
    private final String compressor;

    private WireCallOptions(Integer deadlineMs, String compressor) {
        this.deadlineMs = deadlineMs;
        this.compressor = compressor;
    }

    /**
     * @param deadlineMs the deadline for this call, in milliseconds (must be positive)
     * @return a copy of these options with the per-call deadline set, overriding
     *         the configured {@code requestTimeout} for this call only
     */
    public WireCallOptions withDeadlineMs(int deadlineMs) {
        if (deadlineMs <= 0) {
            throw new IllegalArgumentException(
                    "deadlineMs must be positive but was " + deadlineMs);
        }
        return new WireCallOptions(deadlineMs, this.compressor);
    }

    /**
     * @param compressor the request compressor for this call ({@code identity} or
     *                   {@code gzip}); unsupported values fall back to the client's
     *                   configured compression at call time
     * @return a copy of these options with the per-call compressor set
     */
    public WireCallOptions withCompressor(String compressor) {
        return new WireCallOptions(this.deadlineMs, compressor);
    }

    /**
     * @return the per-call deadline in ms, or {@code null} to inherit the
     *         configured timeout
     */
    public Integer deadlineMs() {
        return deadlineMs;
    }

    /**
     * @return the per-call compressor, or {@code null} to inherit the client's
     *         compression setting
     */
    public String compressor() {
        return compressor;
    }
}
