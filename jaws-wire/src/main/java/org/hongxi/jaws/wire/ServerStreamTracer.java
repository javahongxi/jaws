package org.hongxi.jaws.wire;

/**
 * The {@link StreamTracer} for the server side of a stream.
 * <p>
 * The direction-neutral {@link StreamTracer} events look inbound/outbound
 * from wherever the tracer sits; on the server that means inbound is the
 * request and outbound is the response. Header events are added here because
 * a server answers with either a HEADERS + DATA + trailers sequence or a
 * single trailers-only HEADERS, and monitoring wants to tell those apart.
 * <p>
 * These four events and {@link #callEnded()} are deliberately <em>not</em> a
 * mirror of {@code io.grpc.ServerStreamTracer}, which only has
 * {@code serverCallStarted}: they are named after the points a jaws stream
 * actually reaches, and chosen for what a server-side metric needs over keeping
 * the surface identical. Do not trim them toward grpc's shape.
 *
 * @author shenhongxi
 * @see WireServer#setStreamTracerFactory
 */
public abstract class ServerStreamTracer extends StreamTracer {

    /** A tracer that observes nothing. */
    public static final ServerStreamTracer NOOP = new ServerStreamTracer() {
    };

    /**
     * Request headers have been received and parsed, i.e. the call is now
     * known to this server. The tracer is created at this point, so this is
     * the first event every server-side tracer sees.
     */
    public void inboundHeaders() {
    }

    /**
     * Response HEADERS have been written, i.e. the server committed to a
     * HEADERS + DATA + trailers response instead of a trailers-only one.
     */
    public void outboundHeaders() {
    }

    /**
     * Response trailers have been written, including the trailers-only form
     * where a single HEADERS frame with END_STREAM carries the status.
     */
    public void outboundTrailers() {
    }

    /**
     * The stream is finished with this tracer. Called after
     * {@link #streamClosed(int)}, when the stream channel is going away, so
     * that a tracer can release per-stream state. This is the only server-side
     * event guaranteed to run even when the call ends without any trailers
     * (peer reset, connection dropped mid-call).
     */
    public void callEnded() {
    }

    /**
     * Factory creating one {@link ServerStreamTracer} per inbound stream.
     */
    public abstract static class Factory {

        /**
         * Creates the tracer for an inbound stream whose request headers have
         * just been parsed.
         *
         * @param path the gRPC method path, {@code /service/method}
         * @return the tracer for this stream, never {@code null}; return
         *         {@link ServerStreamTracer#NOOP} to observe nothing
         */
        public abstract ServerStreamTracer newServerStreamTracer(String path);
    }
}
