package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.stream.StreamSource;
import org.hongxi.jaws.transport.StreamSubject;
import org.hongxi.jaws.wire.health.HealthCheckRequest;
import org.hongxi.jaws.wire.health.HealthCheckResponse;
import org.hongxi.jaws.wire.health.HealthCheckResponse.ServingStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client-side interceptor chain on the three streaming shapes and on unary.
 * <p>
 * The load-bearing guarantee is that every outbound request item is routed
 * through the wrapped {@link WireClientCall}, so an interceptor observes each
 * message and the trailing half-close — not merely the opening metadata. The
 * counting assertions below therefore fail if streaming writes DATA frames
 * directly to the stream channel instead of going through the call (that was
 * the regression the earlier design shipped with).
 *
 * @author shenhongxi
 */
@Timeout(60)
class WireClientStreamingInterceptorTest {

    private static final String SERVICE = "test.Echo";
    private static final String MARKER = "x-marker";

    /** method name -> markers observed in the server call context when the handler ran. */
    private final Map<String, List<String>> serverObserved = new ConcurrentHashMap<>();

    private WireServer server;
    private WireClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    // ========================================================================
    // Every outbound item flows through the chain (the core guarantee)
    // ========================================================================

    @Test
    void clientStreamingRoutesEveryItemThroughTheChain() {
        startPair();
        AtomicInteger sent = new AtomicInteger();
        AtomicBoolean halfClosed = new AtomicBoolean();
        client.addInterceptor(counting(sent, halfClosed));

        StreamSubject<Object> outbound = new StreamSubject<>();
        client.requestStream(request("ClientStream"), outbound, HealthCheckResponse.parser());
        outbound.onNext(item("a"));
        outbound.onNext(item("b"));
        outbound.onNext(item("c"));
        outbound.onCompleted();

        assertEquals(3, sent.get(),
                "client-streaming must call the wrapped call once per item, not just open metadata");
        assertTrue(halfClosed.get(), "half-close must reach the interceptor after the last item");
    }

    @Test
    void bidiRoutesEveryItemThroughTheChain() {
        startPair();
        AtomicInteger sent = new AtomicInteger();
        AtomicBoolean halfClosed = new AtomicBoolean();
        client.addInterceptor(counting(sent, halfClosed));

        StreamSubject<Object> outbound = new StreamSubject<>();
        client.requestBidiStream(request("Bidi"), outbound, HealthCheckResponse.parser());
        outbound.onNext(item("a"));
        outbound.onNext(item("b"));

        assertEquals(2, sent.get(), "bidi must route each outbound item through the chain");
    }

    @Test
    void serverStreamingSendsRequestAndHalfCloseThroughTheChain() {
        startPair();
        AtomicInteger sent = new AtomicInteger();
        AtomicBoolean halfClosed = new AtomicBoolean();
        client.addInterceptor(counting(sent, halfClosed));

        client.requestStream(request("ServerStream"), HealthCheckResponse.parser());

        assertEquals(1, sent.get(), "server-streaming sends its single request through the chain");
        assertTrue(halfClosed.get(),
                "server-streaming must half-close through the chain so END_STREAM goes out");
    }

    @Test
    void unaryStillFlowsThroughTheUnifiedChain() {
        startPair();
        AtomicInteger sent = new AtomicInteger();
        AtomicBoolean halfClosed = new AtomicBoolean();
        client.addInterceptor(counting(sent, halfClosed));

        client.request(request("Unary"), HealthCheckResponse.parser());

        assertEquals(1, sent.get(), "unary shares the same call path: one request message");
        assertTrue(halfClosed.get(), "unary now half-closes through the chain (unified send path)");
    }

    // ========================================================================
    // Metadata injected by an interceptor reaches the server on each shape
    // ========================================================================

    @Test
    void serverStreamingInjectsMetadataIntoHeaders() {
        startPair();
        client.addInterceptor((call, next) -> {
            call.putAttachment(MARKER, "ss-1");
            return next.newCall(call);
        });

        client.requestStream(request("ServerStream"), HealthCheckResponse.parser());
        awaitMarker("ServerStream", "ss-1");
    }

    @Test
    void clientStreamingInjectsMetadataOnTheLazyHeadersOpen() {
        startPair();
        client.addInterceptor((call, next) -> {
            call.putAttachment(MARKER, "cs-1");
            return next.newCall(call);
        });

        StreamSubject<Object> outbound = new StreamSubject<>();
        client.requestStream(request("ClientStream"), outbound, HealthCheckResponse.parser());
        outbound.onNext(item("a"));
        outbound.onCompleted();

        awaitMarker("ClientStream", "cs-1");
    }

    @Test
    void bidiInjectsMetadataOnTheLazyHeadersOpen() {
        startPair();
        client.addInterceptor((call, next) -> {
            call.putAttachment(MARKER, "bd-1");
            return next.newCall(call);
        });

        StreamSubject<Object> outbound = new StreamSubject<>();
        client.requestBidiStream(request("Bidi"), outbound, HealthCheckResponse.parser());
        outbound.onNext(item("a"));

        awaitMarker("Bidi", "bd-1");
    }

    @Test
    void streamingChainRunsFirstAddedInterceptorOutermost() {
        startPair();
        List<String> order = new CopyOnWriteArrayList<>();
        client.addInterceptor((call, next) -> {
            order.add("outer");
            return next.newCall(call);
        });
        client.addInterceptor((call, next) -> {
            order.add("inner");
            return next.newCall(call);
        });

        client.requestStream(request("ServerStream"), HealthCheckResponse.parser());
        awaitMarker("ServerStream", null); // no marker; just wait for the handler to run

        assertEquals(List.of("outer", "inner"), order,
                "streaming chain must match unary: first added = outermost");
    }

    // ========================================================================
    // Wiring
    // ========================================================================

    /** An interceptor that tallies the wrapped call's outbound traffic. */
    private WireClientInterceptor counting(AtomicInteger sent, AtomicBoolean halfClosed) {
        return (call, next) -> next.newCall(new ForwardingClientCall(call) {
            @Override
            public void sendMessage(Message request) {
                sent.incrementAndGet();
                super.sendMessage(request);
            }

            @Override
            public void halfClose() {
                halfClosed.set(true);
                super.halfClose();
            }
        });
    }

    private void startPair() {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        WireHandlerRegistry registry = new WireHandlerRegistry();
        registry.register(SERVICE, "Unary", new EchoHandler("Unary"));
        registry.register(SERVICE, "ServerStream", new EchoHandler("ServerStream"));
        registry.register(SERVICE, "ClientStream", new EchoHandler("ClientStream"));
        registry.register(SERVICE, "Bidi", new EchoHandler("Bidi"));

        server = new WireServer(new URL("wire", "0.0.0.0", port, ""), registry);
        server.open();
        client = new WireClient(new URL("wire", "127.0.0.1", port, SERVICE));
        assertTrue(client.open(), "client should connect");
    }

    private DefaultRequest request(String method) {
        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName(SERVICE);
        request.setMethodName(method);
        request.setArguments(new Object[]{item("probe")});
        return request;
    }

    private Message item(String name) {
        return HealthCheckRequest.newBuilder().setService(name).build();
    }

    private void awaitMarker(String method, String marker) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            List<String> seen = serverObserved.get(method);
            if (seen != null && (marker == null || seen.contains(marker))) {
                return;
            }
            sleep(20);
        }
        throw new AssertionError("server never observed marker " + marker
                + " for " + method + ", seen=" + serverObserved);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Records the call-context marker per invocation, answers one response. */
    private final class EchoHandler implements WireMethodHandler {

        private final String method;

        EchoHandler(String method) {
            this.method = method;
        }

        @Override
        public MethodType methodType() {
            return switch (method) {
                case "ServerStream" -> MethodType.SERVER_STREAM;
                case "ClientStream" -> MethodType.CLIENT_STREAM;
                case "Bidi" -> MethodType.BIDIRECTIONAL;
                default -> MethodType.UNARY;
            };
        }

        @Override
        public com.google.protobuf.Parser<? extends Message> getRequestParser() {
            return HealthCheckRequest.parser();
        }

        @Override
        public Message handle(Message request, WireCallContext context) {
            record(context);
            return reply();
        }

        @Override
        public StreamSource<Message> handleStream(Message request, WireCallContext context) {
            record(context);
            return single();
        }

        @Override
        public Message handleClientStream(StreamSource<Message> requestStream, WireCallContext context) {
            record(context);
            requestStream.subscribe(new org.hongxi.jaws.stream.StreamObserver<>() {
                @Override
                public void onNext(Message item) {
                }

                @Override
                public void onError(Throwable throwable) {
                }

                @Override
                public void onCompleted() {
                }
            });
            return reply();
        }

        @Override
        public StreamSource<Message> handleBidiStream(StreamSource<Message> requestStream,
                                                      WireCallContext context) {
            record(context);
            requestStream.subscribe(new org.hongxi.jaws.stream.StreamObserver<>() {
                @Override
                public void onNext(Message item) {
                }

                @Override
                public void onError(Throwable throwable) {
                }

                @Override
                public void onCompleted() {
                }
            });
            return single();
        }

        private void record(WireCallContext context) {
            serverObserved.computeIfAbsent(method, k -> new CopyOnWriteArrayList<>())
                    .add(context.getAttachment(MARKER));
        }

        private StreamSource<Message> single() {
            StreamSubject<Message> subject = new StreamSubject<>();
            subject.onNext(reply());
            subject.onCompleted();
            return subject;
        }

        private Message reply() {
            return HealthCheckResponse.newBuilder().setStatus(ServingStatus.SERVING).build();
        }
    }
}
