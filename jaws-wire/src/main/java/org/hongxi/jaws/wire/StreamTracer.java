package org.hongxi.jaws.wire;

/**
 * Listens to the lifecycle of one gRPC stream to collect metrics.
 * <p>
 * A tracer is created per stream (per retry attempt on the client) and is
 * the floor that transport-neutral observability is built on: message
 * counters, byte histograms and per-call latency all reduce to the events
 * declared here.
 * <p>
 * Only events that jaws-wire can actually observe are declared. gRPC's
 * {@code io.grpc.StreamTracer} additionally has pre-notification callbacks
 * ({@code inboundMessage}/{@code outboundMessage}) and accumulative byte
 * callbacks ({@code inboundWireSize} and friends): the first pair collapses
 * into their {@code ...Read}/{@code ...Sent} counterparts here, because
 * {@link WireFrameCodec#tryExtractFrame} hands out a frame only once it is
 * complete, so "the stream knows about the message" and "the message was
 * fully read" are the same instant; the second pair carries transport-level
 * bytes (HTTP/2 frame headers, HPACK-computed HEADERS, full-stream
 * compression) that a handler sitting on the HTTP/2 <em>stream</em> channel
 * cannot see. Both would be declarations nothing ever fires.
 * <p>
 * Every method has a no-op default, so an implementation overrides only the
 * events it cares about. This class is not thread-safe by contract: the
 * events of one stream are delivered sequentially by the code paths that
 * drive that direction.
 *
 * @author shenhongxi
 * @see ClientStreamTracer
 * @see ServerStreamTracer
 */
public abstract class StreamTracer {

    /**
     * The stream reached a terminal state. Called exactly once per stream.
     *
     * @param status the grpc-status code the call ended with; codes are the
     *               {@code STATUS_*} constants in {@link WireConstants}, and
     *               a locally failed call that never received trailers is
     *               mapped by {@link WireStatus#fromThrowable(Throwable)}
     */
    public void streamClosed(int status) {
    }

    /**
     * An outbound message has been serialized, framed and handed to the
     * transport.
     *
     * @param seqNo            sequential number of the message within this
     *                         direction of the stream, starting from 0
     * @param wireSize         size of the message payload on the wire, i.e.
     *                         the compressed length when the call's encoding
     *                         is not identity; excludes the 5-byte gRPC frame
     *                         header
     * @param uncompressedSize size of the serialized protobuf message before
     *                         compression
     */
    public void outboundMessageSent(int seqNo, long wireSize, long uncompressedSize) {
    }

    /**
     * An inbound message has been fully read from the transport, deframed
     * and decompressed.
     *
     * @param seqNo            sequential number of the message within this
     *                         direction of the stream, starting from 0
     * @param wireSize         size of the message payload as received on the
     *                         wire; excludes the 5-byte gRPC frame header
     * @param uncompressedSize size of the payload after decompression, i.e.
     *                         the serialized protobuf message
     */
    public void inboundMessageRead(int seqNo, long wireSize, long uncompressedSize) {
    }
}
