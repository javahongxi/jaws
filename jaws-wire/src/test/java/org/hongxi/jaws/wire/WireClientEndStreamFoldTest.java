package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.wire.health.HealthCheckRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the client send path's END_STREAM folding: unary and
 * server-streaming calls (one message + immediate half-close) must go out as
 * HEADERS + a single DATA(END_STREAM) — the same shape grpc-java sends — while
 * client-streaming keeps one DATA frame per message and folds END_STREAM into
 * the last one.
 *
 * @author shenhongxi
 */
class WireClientEndStreamFoldTest {

    private static final String SERVICE = "org.hongxi.jaws.wire.FoldService";

    private EmbeddedChannel stream;
    private WireClient client;
    private WireClientCall call;

    @BeforeEach
    void setUp() {
        stream = new EmbeddedChannel();
        client = new WireClient(new org.hongxi.jaws.rpc.URL("wire", "127.0.0.1", 50051, SERVICE));
        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName(SERVICE);
        request.setMethodName("SayHello");
        request.setRequestId(1L);
        newCall(true);
    }

    private void newCall(boolean foldEndStream) {
        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName(SERVICE);
        request.setMethodName("SayHello");
        request.setRequestId(1L);
        call = client.new ClientCallImpl(
                stream, request, "/" + SERVICE + "/SayHello", 30_000,
                Codec.Identity.NONE, WireCallContext.EMPTY,
                t -> { }, ClientStreamTracer.NOOP, foldEndStream);
    }

    @AfterEach
    void tearDown() {
        if (stream != null && stream.isOpen()) {
            stream.finishAndReleaseAll();
        }
        if (client != null) {
            client.close();
        }
    }

    private static Message message(String text) {
        return HealthCheckRequest.newBuilder().setService(text).build();
    }

    /** Drains one outbound frame, asserts it is DATA, returns its protobuf payload. */
    private static byte[] pollDataPayload(EmbeddedChannel stream, boolean expectEndStream,
                                          Message expected) {
        Object outbound = stream.outboundMessages().poll();
        assertTrue(outbound instanceof Http2DataFrame, "expected a DATA frame");
        Http2DataFrame frame = (Http2DataFrame) outbound;
        assertEquals(expectEndStream, frame.isEndStream());
        byte[] bytes = new byte[frame.content().readableBytes()];
        frame.content().readBytes(bytes);
        frame.release();
        // strip the 5-byte gRPC prefix (1B flag + 4B length)
        byte[] payload = new byte[bytes.length - 5];
        System.arraycopy(bytes, 5, payload, 0, payload.length);
        assertArrayEquals(expected.toByteArray(), payload);
        return payload;
    }

    @Test
    void unaryFoldsEndStreamIntoSingleDataFrame() {
        call.sendMessage(message("jaws"));
        call.halfClose();

        assertTrue(stream.outboundMessages().poll() instanceof Http2HeadersFrame,
                "first frame must be HEADERS");
        pollDataPayload(stream, true, message("jaws"));
        assertNull(stream.outboundMessages().poll(),
                "unary must not emit a trailing empty DATA(END_STREAM) frame");
    }

    @Test
    void clientStreamingSendsEmptyEndStreamHalfClose() {
        // Interactive shape: every message goes out immediately (END_STREAM
        // false) so the peer sees it before half-close; half-close emits the
        // terminating empty DATA frame.
        newCall(false);
        call.sendMessage(message("m1"));
        call.sendMessage(message("m2"));
        call.halfClose();

        assertTrue(stream.outboundMessages().poll() instanceof Http2HeadersFrame);
        pollDataPayload(stream, false, message("m1"));
        pollDataPayload(stream, false, message("m2"));
        Object end = stream.outboundMessages().poll();
        assertTrue(end instanceof Http2DataFrame);
        assertTrue(((Http2DataFrame) end).isEndStream());
        assertEquals(0, ((Http2DataFrame) end).content().readableBytes());
        ((Http2DataFrame) end).release();
        assertNull(stream.outboundMessages().poll());
    }

    @Test
    void halfCloseWithoutMessageSendsEmptyEndStream() {
        // client-streaming with no item: HEADERS + empty DATA(END_STREAM)
        newCall(false);
        call.halfClose();

        assertTrue(stream.outboundMessages().poll() instanceof Http2HeadersFrame);
        Object data = stream.outboundMessages().poll();
        assertTrue(data instanceof Http2DataFrame);
        assertTrue(((Http2DataFrame) data).isEndStream());
        assertEquals(0, ((Http2DataFrame) data).content().readableBytes());
        ((Http2DataFrame) data).release();
    }

    @Test
    void halfCloseIsIdempotent() {
        call.sendMessage(message("jaws"));
        call.halfClose();
        int count = stream.outboundMessages().size();
        call.halfClose();
        assertEquals(count, stream.outboundMessages().size(),
                "repeated half-close must not emit extra frames");
    }
}
