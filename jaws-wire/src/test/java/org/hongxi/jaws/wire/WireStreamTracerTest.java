package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.wire.health.HealthCheckRequest;
import org.hongxi.jaws.wire.health.HealthCheckResponse;
import org.hongxi.jaws.wire.health.HealthCheckResponse.ServingStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;
import org.hongxi.jaws.transport.StreamSubject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Event-sequence and byte-accounting tests for the per-stream tracers, driven
 * through {@link WireStreamServerHandler} on an {@link EmbeddedChannel} and
 * through the client-side response handlers.
 *
 * @author shenhongxi
 */
class WireStreamTracerTest {

    private static final int MAX_MESSAGE_SIZE = 4 * 1024 * 1024;

    /** Same-thread executor so dispatch runs inline with writeInbound. */
    private static final ExecutorService DIRECT_EXECUTOR = new AbstractExecutorService() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    };

    private static final HealthCheckRequest REQUEST =
            HealthCheckRequest.newBuilder().setService("demo").build();

    /**
     * Records every event the tracer sees as a line, so a test asserts the
     * whole observed lifecycle in one comparison instead of six. Copied into a
     * concurrent list because the end-to-end case records on a Netty thread.
     */
    private static final class RecordingServerTracer extends ServerStreamTracer {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void inboundHeaders() {
            events.add("inboundHeaders");
        }

        @Override
        public void outboundHeaders() {
            events.add("outboundHeaders");
        }

        @Override
        public void outboundTrailers() {
            events.add("outboundTrailers");
        }

        @Override
        public void inboundMessageRead(int seqNo, long wireSize, long uncompressedSize) {
            events.add("inboundMessageRead " + seqNo + " " + wireSize + " " + uncompressedSize);
        }

        @Override
        public void outboundMessageSent(int seqNo, long wireSize, long uncompressedSize) {
            events.add("outboundMessageSent " + seqNo + " " + wireSize + " " + uncompressedSize);
        }

        @Override
        public void streamClosed(int status) {
            events.add("streamClosed " + status);
        }

        @Override
        public void callEnded() {
            events.add("callEnded");
        }
    }

    private static Http2HeadersFrame requestHeaders(String path, Http2Headers extra) {
        Http2Headers headers = new DefaultHttp2Headers()
                .method("POST")
                .scheme("http")
                .path(path)
                .authority("localhost")
                .set(WireConstants.HEADER_CONTENT_TYPE, WireConstants.CONTENT_TYPE_GRPC)
                .set(WireConstants.HEADER_TE, WireConstants.TE_TRAILERS);
        for (var entry : extra) {
            headers.set(entry.getKey(), entry.getValue());
        }
        return new DefaultHttp2HeadersFrame(headers, false);
    }

    private static Http2HeadersFrame requestHeaders(String path) {
        return requestHeaders(path, new DefaultHttp2Headers());
    }

    private static WireMethodHandler echoHandler() {
        return new WireMethodHandler() {
            @Override
            public Message handle(Message request) {
                return HealthCheckResponse.newBuilder().setStatus(ServingStatus.SERVING).build();
            }

            @Override
            public Parser<? extends Message> getRequestParser() {
                return HealthCheckRequest.parser();
            }
        };
    }

    /** A server handler whose every stream reports into {@code recorder}. */
    private static EmbeddedChannel tracedServer(RecordingServerTracer recorder, String compression) {
        WireHandlerRegistry registry = new WireHandlerRegistry();
        registry.register("test.Health", "Echo", echoHandler());
        return new EmbeddedChannel(new WireStreamServerHandler(
                new WireCallDispatcher.HandlerCallDispatcher(registry, Set.of()),
                null, DIRECT_EXECUTOR, MAX_MESSAGE_SIZE, 0, compression,
                new ServerStreamTracer.Factory() {
                    @Override
                    public ServerStreamTracer newServerStreamTracer(String path) {
                        return recorder;
                    }
                }));
    }

    @Test
    void unaryCallReportsTheWholeStreamInOrder() throws Exception {
        RecordingServerTracer recorder = new RecordingServerTracer();
        EmbeddedChannel ch = tracedServer(recorder, null);

        ch.writeInbound(requestHeaders("/test.Health/Echo"));
        ch.writeInbound(new DefaultHttp2DataFrame(
                WireFrameCodec.encode(REQUEST, ch.alloc()), true));

        // The identity-encoded request is 6 bytes on the wire and 6 bytes
        // uncompressed; the SERVING response is 2 bytes either way.
        assertEquals(List.of(
                        "inboundHeaders",
                        "inboundMessageRead 0 6 6",
                        "outboundHeaders",
                        "outboundMessageSent 0 2 2",
                        "outboundTrailers",
                        "streamClosed 0"),
                recorder.events);

        ch.finishAndReleaseAll();
        assertEquals("callEnded", recorder.events.get(recorder.events.size() - 1));
    }

    @Test
    void compressedRequestReportsWireSizeSeparatelyFromUncompressedSize() throws Exception {
        RecordingServerTracer recorder = new RecordingServerTracer();
        EmbeddedChannel ch = tracedServer(recorder, null);

        Http2Headers extra = new DefaultHttp2Headers()
                .set(WireConstants.GRPC_ENCODING, WireConstants.ENCODING_GZIP)
                .set(WireConstants.GRPC_ACCEPT_ENCODING, WireConstants.ENCODING_GZIP);
        ByteBuf frame = WireFrameCodec.encode(REQUEST, ch.alloc(), WireConstants.ENCODING_GZIP);
        int wireSize = frame.readableBytes() - WireConstants.GRPC_HEADER_SIZE;
        ch.writeInbound(requestHeaders("/test.Health/Echo", extra));
        ch.writeInbound(new DefaultHttp2DataFrame(frame, true));

        // gzip inflates a 6-byte message, which is exactly why the two numbers
        // must be reported apart rather than as one "size"
        assertTrue(wireSize > REQUEST.getSerializedSize(),
                "test premise: gzip of a 6-byte message is larger than the message");
        assertEquals("inboundMessageRead 0 " + wireSize + " " + REQUEST.getSerializedSize(),
                recorder.events.get(1));
        ch.finishAndReleaseAll();
    }

    @Test
    void outboundWireSizeMatchesTheFrameActuallyWritten() throws Exception {
        RecordingServerTracer recorder = new RecordingServerTracer();
        EmbeddedChannel ch = tracedServer(recorder, WireConstants.ENCODING_GZIP);

        // The client advertises gzip, so the negotiated response is compressed
        Http2Headers extra = new DefaultHttp2Headers()
                .set(WireConstants.GRPC_ACCEPT_ENCODING, WireConstants.ACCEPT_ENCODINGS);
        ch.writeInbound(requestHeaders("/test.Health/Echo", extra));
        ch.writeInbound(new DefaultHttp2DataFrame(
                WireFrameCodec.encode(REQUEST, ch.alloc()), true));

        ch.readOutbound(); // HEADERS
        Http2DataFrame dataFrame = ch.readOutbound();
        int written = dataFrame.content().readableBytes() - WireConstants.GRPC_HEADER_SIZE;

        String reported = recorder.events.stream()
                .filter(e -> e.startsWith("outboundMessageSent"))
                .findFirst().orElseThrow();
        assertEquals("outboundMessageSent 0 " + written + " "
                        + HealthCheckResponse.newBuilder()
                        .setStatus(ServingStatus.SERVING).build().getSerializedSize(),
                reported, "tracer must report the bytes that really went out");
        ch.finishAndReleaseAll();
    }

    @Test
    void callerCancellationClosesTheStreamExactlyOnce() throws Exception {
        RecordingServerTracer recorder = new RecordingServerTracer();
        EmbeddedChannel ch = tracedServer(recorder, null);

        ch.writeInbound(requestHeaders("/test.Health/Echo"));
        // RST_STREAM reaches a stream handler as a user event, never as a message
        ch.pipeline().fireUserEventTriggered(new DefaultHttp2ResetFrame(Http2Error.CANCEL));
        ch.finishAndReleaseAll();

        long closedEvents = recorder.events.stream()
                .filter(e -> e.startsWith("streamClosed"))
                .count();
        assertEquals(1, closedEvents, "streamClosed is exactly once per stream");
        assertTrue(recorder.events.contains("streamClosed " + WireConstants.STATUS_CANCELED),
                "a reset call ends CANCELED: " + recorder.events);
        assertEquals("callEnded", recorder.events.get(recorder.events.size() - 1));
    }

    @Test
    void streamingResponsesNumberEachMessage() throws Exception {
        RecordingServerTracer recorder = new RecordingServerTracer();
        WireHandlerRegistry registry = new WireHandlerRegistry();
        registry.register("test.Health", "Stream", new WireMethodHandler() {
            @Override
            public Message handle(Message request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Parser<? extends Message> getRequestParser() {
                return HealthCheckRequest.parser();
            }

            @Override
            public StreamSource<Message> handleStream(Message request, WireCallContext context) {
                return observer -> {
                    for (int i = 0; i < 3; i++) {
                        observer.onNext(HealthCheckResponse.newBuilder()
                                .setStatus(ServingStatus.SERVING).build());
                    }
                    observer.onCompleted();
                };
            }

            @Override
            public MethodType methodType() {
                return MethodType.SERVER_STREAM;
            }
        });
        EmbeddedChannel ch = new EmbeddedChannel(new WireStreamServerHandler(
                new WireCallDispatcher.HandlerCallDispatcher(registry, Set.of()),
                null, DIRECT_EXECUTOR, MAX_MESSAGE_SIZE, 0, null,
                new ServerStreamTracer.Factory() {
                    @Override
                    public ServerStreamTracer newServerStreamTracer(String path) {
                        return recorder;
                    }
                }));

        ch.writeInbound(requestHeaders("/test.Health/Stream"));
        ch.writeInbound(new DefaultHttp2DataFrame(
                WireFrameCodec.encode(REQUEST, ch.alloc()), true));

        assertEquals(List.of("inboundHeaders", "inboundMessageRead 0 6 6", "outboundHeaders",
                        "outboundMessageSent 0 2 2", "outboundMessageSent 1 2 2",
                        "outboundMessageSent 2 2 2", "outboundTrailers", "streamClosed 0"),
                recorder.events, "one numbered event per streamed message, headers once");
        ch.finishAndReleaseAll();
    }

    /** Records the client side of a stream, one line per event. */
    private static final class RecordingClientTracer extends ClientStreamTracer {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void outboundHeaders() {
            events.add("outboundHeaders");
        }

        @Override
        public void inboundHeaders() {
            events.add("inboundHeaders");
        }

        @Override
        public void inboundTrailers() {
            events.add("inboundTrailers");
        }

        @Override
        public void inboundMessageRead(int seqNo, long wireSize, long uncompressedSize) {
            events.add("inboundMessageRead " + seqNo + " " + wireSize + " " + uncompressedSize);
        }

        @Override
        public void outboundMessageSent(int seqNo, long wireSize, long uncompressedSize) {
            events.add("outboundMessageSent " + seqNo + " " + wireSize + " " + uncompressedSize);
        }

        @Override
        public void streamClosed(int status) {
            events.add("streamClosed " + status);
        }
    }

    /**
     * The factories configured on {@link WireClient} and {@link WireServer}
     * really reach the streams those classes create, over a loopback socket
     * rather than an embedded channel.
     */
    @Test
    void configuredTracersDriveARealCallOnBothSides() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        WireHandlerRegistry registry = new WireHandlerRegistry();
        registry.register("test.Health", "Echo", echoHandler());

        RecordingServerTracer serverTracer = new RecordingServerTracer();
        WireServer server = new WireServer(new URL("wire", "0.0.0.0", port, ""), registry);
        server.setStreamTracerFactory(new ServerStreamTracer.Factory() {
            @Override
            public ServerStreamTracer newServerStreamTracer(String path) {
                assertEquals("/test.Health/Echo", path, "factory is keyed on the method path");
                return serverTracer;
            }
        });
        server.open();

        RecordingClientTracer clientTracer = new RecordingClientTracer();
        WireClient client = new WireClient(new URL("wire", "127.0.0.1", port, "test.Health"));
        client.setStreamTracerFactory(new ClientStreamTracer.Factory() {
            @Override
            public ClientStreamTracer newClientStreamTracer(String path) {
                assertEquals("/test.Health/Echo", path);
                return clientTracer;
            }
        });
        assertTrue(client.open(), "client should connect");
        try {
            DefaultRequest request = new DefaultRequest();
            request.setInterfaceName("test.Health");
            request.setMethodName("Echo");
            request.setArguments(new Object[]{REQUEST});
            Response response = client.request(request, HealthCheckResponse.parser(),
                    WireCallOptions.DEFAULT);
            assertEquals(ServingStatus.SERVING,
                    ((HealthCheckResponse) response.getValue()).getStatus());

            assertEquals(List.of("outboundHeaders", "outboundMessageSent 0 6 6",
                            "inboundHeaders", "inboundTrailers", "inboundMessageRead 0 2 2",
                            "streamClosed 0"),
                    clientTracer.events, "client sees its own four header/message events");
            // The server has already framed the response by now; callEnded may
            // still be pending on its event loop, so only the request half is
            // asserted here
            assertTrue(serverTracer.events.contains("inboundMessageRead 0 6 6"),
                    "server-side tracer saw the request: " + serverTracer.events);
        } finally {
            client.close();
            server.close();
        }
    }
}
