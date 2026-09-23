package org.hongxi.jaws.wire;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.rpc.Status;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import org.hongxi.jaws.transport.StreamSubject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the gRPC rich-error loop on the CLIENT side: a {@code grpc-status-details-bin}
 * trailer (a serialized {@link Status}, possibly carrying {@link Any} details) is
 * decoded and surfaced to the caller on a {@link WireStatusException}, for both the
 * streaming handler (behavioural, via {@link EmbeddedChannel}) and the status→exception
 * mapping (unit).
 */
class WireRichErrorClientTest {

    private static Status statusWithDetail() {
        return Status.newBuilder()
                .setCode(WireConstants.STATUS_NOT_FOUND) // 5
                .setMessage("no such widget")
                .addDetails(Any.newBuilder()
                        .setTypeUrl("type.googleapis.com/jaws.test.Detail")
                        .setValue(ByteString.copyFromUtf8("widget-42"))
                        .build())
                .build();
    }

    @Test
    void streamingHandlerSurfacesRichStatusOnException() {
        Status detail = statusWithDetail();
        DefaultHttp2Headers trailers = new DefaultHttp2Headers();
        trailers.set(WireConstants.GRPC_STATUS, "5");
        trailers.set(WireConstants.GRPC_MESSAGE, "no such widget");
        trailers.set(WireErrorDetails.GRPC_STATUS_DETAILS_BIN, WireErrorDetails.encode(detail));

        AtomicReference<Throwable> seen = new AtomicReference<>();
        StreamSubject<Object> observer = new StreamSubject<>() {
            @Override public void onNext(Object item) { }
            @Override public void onError(Throwable t) { seen.set(t); }
            @Override public void onCompleted() { }
        };

        EmbeddedChannel ch = new EmbeddedChannel(
                new WireStreamStreamingHandler(null, observer, 4 * 1024 * 1024, 0,
                        ClientStreamTracer.NOOP));
        // Trailers HEADERS with END_STREAM, no DATA — drives completeOrFail directly.
        ch.writeInbound(new DefaultHttp2HeadersFrame(trailers, true));

        Throwable t = seen.get();
        assertNotNull(t, "a non-OK grpc-status must fail the stream");
        WireStatusException wse = assertInstanceOf(WireStatusException.class, t,
                "client must surface a rich WireStatusException, not a bare exception");
        assertEquals(5, wse.getGrpcStatus());
        assertNotNull(wse.getStatusDetails(), "grpc-status-details-bin must be decoded onto the exception");
        assertEquals(1, wse.getStatusDetails().getDetailsCount(),
                "the Any detail must survive the encode/decode round-trip");
        assertEquals("type.googleapis.com/jaws.test.Detail",
                wse.getStatusDetails().getDetails(0).getTypeUrl());
    }

    @Test
    void exceptionOmitsDetailsWhenServerSendsNone() {
        DefaultHttp2Headers trailers = new DefaultHttp2Headers();
        trailers.set(WireConstants.GRPC_STATUS, "13");
        trailers.set(WireConstants.GRPC_MESSAGE, "boom"); // no details-bin header

        AtomicReference<Throwable> seen = new AtomicReference<>();
        StreamSubject<Object> observer = new StreamSubject<>() {
            @Override public void onNext(Object item) { }
            @Override public void onError(Throwable t) { seen.set(t); }
            @Override public void onCompleted() { }
        };
        EmbeddedChannel ch = new EmbeddedChannel(
                new WireStreamStreamingHandler(null, observer, 4 * 1024 * 1024, 0,
                        ClientStreamTracer.NOOP));
        ch.writeInbound(new DefaultHttp2HeadersFrame(trailers, true));

        Throwable t = seen.get();
        assertNotNull(t);
        assertInstanceOf(WireStatusException.class, t);
        assertNull(((WireStatusException) t).getStatusDetails(),
                "no details-bin => null rich Status, but code path still typed");
        assertEquals(13, ((WireStatusException) t).getGrpcStatus());
    }

    @Test
    void toExceptionCarriesStatusAndErrorCodesAreMapped() {
        RuntimeException deadline = WireStatus.toException(
                WireConstants.STATUS_DEADLINE_EXCEEDED, "too slow", statusWithDetail());
        assertInstanceOf(WireStatusException.class, deadline);
        // deadline must keep the jaws SERVICE_TIMEOUT mapping while also carrying details
        assertTrue(deadline.getMessage().contains("DEADLINE_EXCEEDED"));
        assertEquals(1, ((WireStatusException) deadline).getStatusDetails().getDetailsCount());
    }

    @Test
    void errorDetailsRoundTripThroughBase64() {
        Status original = statusWithDetail();
        Status decoded = WireErrorDetails.decode(WireErrorDetails.encode(original));
        assertNotNull(decoded);
        assertEquals(original.getCode(), decoded.getCode());
        assertEquals(original.getMessage(), decoded.getMessage());
        assertEquals(original.getDetailsList(), decoded.getDetailsList());
    }
}
