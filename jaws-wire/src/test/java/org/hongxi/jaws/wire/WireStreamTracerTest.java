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
import org.hongxi.jaws.common.UrlParam;
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
import java.util.concurrent.CountDownLatch;
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

    /** Framing and server configuration take negotiated codecs, not names. */
    private static final Codec GZIP = new Codec.Gzip();

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
    private static EmbeddedChannel tracedServer(RecordingServerTracer recorder,
                                                Compressor responseCompressor) {
        WireHandlerRegistry registry = new WireHandlerRegistry();
        registry.register("test.Health", "Echo", echoHandler());
        return new EmbeddedChannel(new WireStreamServerHandler(
                new WireCallDispatcher.HandlerCallDispatcher(registry, Set.of()),
                null, DIRECT_EXECUTOR, MAX_MESSAGE_SIZE, 0, responseCompressor,
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
        ByteBuf frame = WireFrameCodec.encode(REQUEST, ch.alloc(), GZIP);
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
        EmbeddedChannel ch = tracedServer(recorder, GZIP);

        // The client advertises gzip, so the negotiated response is compressed
        Http2Headers extra = new DefaultHttp2Headers()
                .set(WireConstants.GRPC_ACCEPT_ENCODING, WireConstants.ENCODING_GZIP);
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

    /**
     * Drive a real client against a server configured with gzip and return what
     * the server-side tracer recorded.
     *
     * @param advertiseGzip whether the client offers gzip; offering nothing at
     *                      all is what a non-accepting peer looks like on the wire
     * @return the recorded event lines
     */
    private static List<String> negotiateResponseEncoding(boolean advertiseGzip) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        URL serverUrl = new URL("wire", "0.0.0.0", port, "");
        serverUrl.addParameter(UrlParam.Transport.COMPRESSION.getName(), WireConstants.ENCODING_GZIP);

        WireHandlerRegistry registry = new WireHandlerRegistry();
        registry.register("test.Health", "Echo", echoHandler());
        RecordingServerTracer serverTracer = new RecordingServerTracer();
        WireServer server = new WireServer(serverUrl, registry);
        server.setStreamTracerFactory(new ServerStreamTracer.Factory() {
            @Override
            public ServerStreamTracer newServerStreamTracer(String path) {
                return serverTracer;
            }
        });
        server.open();

        WireClient client = new WireClient(new URL("wire", "127.0.0.1", port, "test.Health"));
        if (!advertiseGzip) {
            // No advertisement at all, not even identity: the server must treat
            // this peer as accepting nothing
            client.setDecompressorRegistry(DecompressorRegistry.emptyInstance());
        }
        assertTrue(client.open(), "client should connect");
        try {
            DefaultRequest request = new DefaultRequest();
            request.setInterfaceName("test.Health");
            request.setMethodName("Echo");
            request.setArguments(new Object[]{REQUEST});
            Response response = client.request(request, HealthCheckResponse.parser(),
                    WireCallOptions.DEFAULT);
            assertEquals(ServingStatus.SERVING,
                    ((HealthCheckResponse) response.getValue()).getStatus(),
                    "the call must succeed either way");
            return serverTracer.events;
        } finally {
            client.close();
            server.close();
        }
    }

    private static String firstOutbound(List<String> events) {
        return events.stream()
                .filter(e -> e.startsWith("outboundMessageSent"))
                .findFirst().orElseThrow(() -> new AssertionError("no outbound event: " + events));
    }

    private static final HealthCheckResponse RESPONSE =
            HealthCheckResponse.newBuilder().setStatus(ServingStatus.SERVING).build();

    /** A loopback server plus a client whose every stream reports into one tracer. */
    private record TracedPair(WireServer server, WireClient client) {
        void close() {
            client.close();
            server.close();
        }
    }

    private static TracedPair tracedPair(WireHandlerRegistry registry,
                                        RecordingClientTracer tracer) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        WireServer server = new WireServer(new URL("wire", "0.0.0.0", port, ""), registry);
        server.open();
        WireClient client = new WireClient(new URL("wire", "127.0.0.1", port, "test.Health"));
        client.setStreamTracerFactory(new ClientStreamTracer.Factory() {
            @Override
            public ClientStreamTracer newClientStreamTracer(String path) {
                return tracer;
            }
        });
        assertTrue(client.open(), "client should connect");
        return new TracedPair(server, client);
    }

    private static DefaultRequest request(String method) {
        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName("test.Health");
        request.setMethodName(method);
        request.setArguments(new Object[]{REQUEST});
        return request;
    }

    /** Event names only, ignoring the numbers so assertions stay about order. */
    private static List<String> namesOf(List<String> events) {
        return events.stream().map(e -> e.split(" ")[0]).toList();
    }

    private static long count(List<String> events, String prefix) {
        return events.stream().filter(e -> e.startsWith(prefix)).count();
    }

    /**
     * Unary only ever sends and receives one message, so it cannot tell an
     * off-by-one in the per-direction counters from a working counter. These
     * three cover the shapes that actually number messages on the client side.
     */
    @Test
    void serverStreamNumbersEveryInboundMessageOnTheClient() throws Exception {
        WireHandlerRegistry registry = new WireHandlerRegistry();
        registry.register("test.Health", "ServerStream", new WireMethodHandler() {
            @Override
            public MethodType methodType() {
                return MethodType.SERVER_STREAM;
            }

            @Override
            public StreamSource<Message> handleStream(Message request) {
                StreamSubject<Message> out = new StreamSubject<>();
                out.onNext(RESPONSE);
                out.onNext(RESPONSE);
                out.onNext(RESPONSE);
                out.onCompleted();
                return out;
            }

            @Override
            public Parser<? extends Message> getRequestParser() {
                return HealthCheckRequest.parser();
            }
        });
        RecordingClientTracer tracer = new RecordingClientTracer();
        TracedPair pair = tracedPair(registry, tracer);
        try {
            CountDownLatch done = new CountDownLatch(1);
            pair.client().requestStream(request("ServerStream"), HealthCheckResponse.parser())
                    .subscribe(new StreamObserver<>() {
                        @Override
                        public void onNext(Object item) {
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            done.countDown();
                        }

                        @Override
                        public void onCompleted() {
                            done.countDown();
                        }
                    });
            assertTrue(done.await(10, TimeUnit.SECONDS), "the stream should terminate");

            assertEquals(3, count(tracer.events, "inboundMessageRead"),
                    "one numbered event per streamed response: " + tracer.events);
            assertTrue(tracer.events.containsAll(List.of("inboundMessageRead 0 2 2",
                    "inboundMessageRead 1 2 2", "inboundMessageRead 2 2 2")), "" + tracer.events);
            // A server stream is one request message, sent before any reply
            assertEquals(List.of("outboundHeaders", "outboundMessageSent", "inboundHeaders",
                            "inboundMessageRead", "inboundMessageRead", "inboundMessageRead",
                            "inboundTrailers", "streamClosed"),
                    namesOf(tracer.events));
        } finally {
            pair.close();
        }
    }

    @Test
    void clientStreamNumbersEveryOutboundMessageOnTheClient() throws Exception {
        WireHandlerRegistry registry = new WireHandlerRegistry();
        registry.register("test.Health", "ClientStream", new WireMethodHandler() {
            @Override
            public MethodType methodType() {
                return MethodType.CLIENT_STREAM;
            }

            @Override
            public Message handleClientStream(StreamSource<Message> requestStream) {
                CountDownLatch drained = new CountDownLatch(1);
                requestStream.subscribe(new StreamObserver<>() {
                    @Override
                    public void onNext(Message item) {
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        drained.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        drained.countDown();
                    }
                });
                try {
                    drained.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return RESPONSE;
            }

            @Override
            public Parser<? extends Message> getRequestParser() {
                return HealthCheckRequest.parser();
            }
        });
        RecordingClientTracer tracer = new RecordingClientTracer();
        TracedPair pair = tracedPair(registry, tracer);
        try {
            StreamSubject<Object> outbound = new StreamSubject<>();
            CountDownLatch done = new CountDownLatch(1);
            pair.client().requestStream(request("ClientStream"), outbound,
                            HealthCheckResponse.parser())
                    .subscribe(new StreamObserver<>() {
                        @Override
                        public void onNext(Object item) {
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            done.countDown();
                        }

                        @Override
                        public void onCompleted() {
                            done.countDown();
                        }
                    });
            outbound.onNext(REQUEST);
            outbound.onNext(REQUEST);
            outbound.onNext(REQUEST);
            outbound.onCompleted();
            assertTrue(done.await(10, TimeUnit.SECONDS), "the call should terminate");

            assertTrue(tracer.events.containsAll(List.of("outboundMessageSent 0 6 6",
                            "outboundMessageSent 1 6 6", "outboundMessageSent 2 6 6")),
                    "one numbered event per item the caller pushed: " + tracer.events);
            assertEquals(1, count(tracer.events, "outboundHeaders"),
                    "HEADERS are written once, with the first item");
            assertEquals(1, count(tracer.events, "streamClosed"), "closed exactly once");
        } finally {
            pair.close();
        }
    }

    @Test
    void bidiNumbersBothDirectionsOnTheClient() throws Exception {
        WireHandlerRegistry registry = new WireHandlerRegistry();
        registry.register("test.Health", "Bidi", new WireMethodHandler() {
            @Override
            public MethodType methodType() {
                return MethodType.BIDIRECTIONAL;
            }

            @Override
            public StreamSource<Message> handleBidiStream(StreamSource<Message> requestStream) {
                StreamSubject<Message> out = new StreamSubject<>();
                requestStream.subscribe(new StreamObserver<>() {
                    @Override
                    public void onNext(Message item) {
                        out.onNext(RESPONSE);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        out.onError(throwable);
                    }

                    @Override
                    public void onCompleted() {
                        out.onCompleted();
                    }
                });
                return out;
            }

            @Override
            public Parser<? extends Message> getRequestParser() {
                return HealthCheckRequest.parser();
            }
        });
        RecordingClientTracer tracer = new RecordingClientTracer();
        TracedPair pair = tracedPair(registry, tracer);
        try {
            StreamSubject<Object> outbound = new StreamSubject<>();
            CountDownLatch done = new CountDownLatch(1);
            pair.client().requestBidiStream(request("Bidi"), outbound,
                            HealthCheckResponse.parser())
                    .subscribe(new StreamObserver<>() {
                        @Override
                        public void onNext(Object item) {
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            done.countDown();
                        }

                        @Override
                        public void onCompleted() {
                            done.countDown();
                        }
                    });
            outbound.onNext(REQUEST);
            outbound.onNext(REQUEST);
            outbound.onCompleted();
            assertTrue(done.await(10, TimeUnit.SECONDS), "the stream should terminate");

            // The two directions interleave freely, so this asserts each one's
            // numbering rather than a global order
            assertTrue(tracer.events.containsAll(List.of("outboundMessageSent 0 6 6",
                    "outboundMessageSent 1 6 6")), "" + tracer.events);
            assertTrue(tracer.events.containsAll(List.of("inboundMessageRead 0 2 2",
                    "inboundMessageRead 1 2 2")), "" + tracer.events);
            assertEquals("outboundHeaders", namesOf(tracer.events).get(0));
            assertEquals("streamClosed", namesOf(tracer.events).get(tracer.events.size() - 1));
            assertEquals(1, count(tracer.events, "streamClosed"));
        } finally {
            pair.close();
        }
    }

    /**
     * The negotiation matrix over a real socket rather than an embedded channel:
     * gzip costs about twenty bytes on a message this small, so a differing
     * wire and raw size is positive evidence a codec actually ran.
     */
    @Test
    void peerAdvertisingGzipIsServedCompressed() throws Exception {
        String outbound = firstOutbound(negotiateResponseEncoding(true));
        String[] parts = outbound.split(" ");
        assertTrue(!parts[2].equals(parts[3]),
                "wire and raw sizes must differ when gzip ran: " + outbound);
    }

    @Test
    void peerAdvertisingNothingIsServedUncompressed() throws Exception {
        String outbound = firstOutbound(negotiateResponseEncoding(false));
        String[] parts = outbound.split(" ");
        assertEquals(parts[2], parts[3],
                "an unadvertised codec must be downgraded, not used: " + outbound);
    }
}
