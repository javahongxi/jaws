package org.hongxi.jaws.wire;

/**
 * The {@link StreamTracer} for the client side of a stream.
 * <p>
 * Adds the header events a caller needs to tell apart "the request never
 * left", "the request left but nothing came back" and "the server answered",
 * which the direction-neutral {@link StreamTracer} cannot express.
 * <p>
 * A fresh tracer is created for every stream, which on the client means
 * every <em>attempt</em>: a retried call produces one tracer per try, so
 * per-attempt latency is not blended across retries.
 *
 * @author shenhongxi
 * @see WireClient#setStreamTracerFactory
 */
public abstract class ClientStreamTracer extends StreamTracer {

    /** A tracer that observes nothing. */
    public static final ClientStreamTracer NOOP = new ClientStreamTracer() {
    };

    /**
     * The request HEADERS frame has been written to the stream. This is the
     * point where the call becomes visible to the server; it happens on the
     * first {@code sendMessage} because HEADERS are written lazily so that
     * interceptors can still mutate attachments.
     */
    public void outboundHeaders() {
    }

    /**
     * The initial response HEADERS frame has been received, i.e. the server
     * started answering. Not called for a trailers-only response, which
     * reports {@link #inboundTrailers()} instead.
     */
    public void inboundHeaders() {
    }

    /**
     * The response trailers HEADERS frame has been received. Always precedes
     * {@link #streamClosed(int)}, which carries the parsed status.
     */
    public void inboundTrailers() {
    }

    /**
     * Factory creating one {@link ClientStreamTracer} per outbound stream.
     */
    public abstract static class Factory {

        /**
         * Creates the tracer for a stream that is about to be opened.
         *
         * @param path the gRPC method path, {@code /service/method}
         * @return the tracer for this stream, never {@code null}; return
         *         {@link ClientStreamTracer#NOOP} to observe nothing
         */
        public abstract ClientStreamTracer newClientStreamTracer(String path);
    }
}
