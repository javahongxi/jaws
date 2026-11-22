package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;
import org.hongxi.jaws.transport.StreamSubject;
import org.hongxi.jaws.wire.health.HealthCheckRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A connection's death must reach every stream still open on it.
 * <p>
 * The multiplex handler closes stream channels only when the HTTP/2 codec
 * reports their streams CLOSED, and the disconnect teardown paths remove the
 * codec before the connection closes (to keep a broken socket from being
 * asked to carry a GOAWAY). After that removal no stream event ever reaches
 * the children: without a connection-level fallback they sit open until GC
 * collects them, leaking whatever the stream handler still holds (its
 * accumulator buffer, its in-flight slot). The in-flight counter is the
 * observable here — it is given back only in the stream handler's
 * {@code channelInactive}, so a count that never returns to zero is exactly
 * the orphaned-stream defect.
 *
 * @author shenhongxi
 */
@Timeout(60)
class WireStreamOrphanTeardownTest {

    private static final String SERVICE = "test.Hold";

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

    @Test
    void anOpenBidiStreamIsTornDownWhenTheConnectionIsReset() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        CountDownLatch firstItem = new CountDownLatch(1);
        WireHandlerRegistry registry = new WireHandlerRegistry();
        registry.register(SERVICE, "Hold", new HoldOpenHandler(firstItem));
        server = new WireServer(new URL("wire", "127.0.0.1", port, ""), registry);
        assertTrue(server.open(), "wire server should bind port " + port);
        client = new WireClient(new URL("wire", "127.0.0.1", port, SERVICE));
        assertTrue(client.open(), "wire client should connect");

        StreamSubject<Object> outbound = new StreamSubject<>();
        client.requestBidiStream(request(), outbound, HealthCheckRequest.parser());
        outbound.onNext(HealthCheckRequest.newBuilder().setService("probe").build());
        assertTrue(firstItem.await(5, TimeUnit.SECONDS), "the bidi handler should see the first item");
        awaitInflight(1);

        // Kill the TCP connection with a reset rather than a polite close: the
        // server's IOException teardown removes http2_codec before closing,
        // which is precisely the path that used to orphan the stream channel.
        Channel connection = client.connectionChannel();
        connection.config().setOption(ChannelOption.SO_LINGER, 0);
        connection.close().awaitUninterruptibly(5, TimeUnit.SECONDS);

        awaitInflight(0);
    }

    /**
     * A bidi handler that answers nothing and never completes: the stream stays
     * open until the connection itself goes away, which is the shape a nacos
     * BiRequestStream holds for the whole life of a client connection.
     */
    private static final class HoldOpenHandler implements WireMethodHandler {
        private final CountDownLatch firstItem;

        HoldOpenHandler(CountDownLatch firstItem) {
            this.firstItem = firstItem;
        }

        @Override
        public MethodType methodType() {
            return MethodType.BIDIRECTIONAL;
        }

        @Override
        public Parser<? extends Message> getRequestParser() {
            return HealthCheckRequest.parser();
        }

        @Override
        public StreamSource<Message> handleBidiStream(StreamSource<Message> requestStream,
                                                      WireCallContext context) {
            requestStream.subscribe(new StreamObserver<>() {
                @Override
                public void onNext(Message item) {
                    firstItem.countDown();
                }

                @Override
                public void onError(Throwable throwable) {
                }

                @Override
                public void onCompleted() {
                }
            });
            // Never completed: no response item, no trailers, the stream is
            // still open when the connection is reset.
            return new StreamSubject<>();
        }
    }

    private static DefaultRequest request() {
        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName(SERVICE);
        request.setMethodName("Hold");
        request.setArguments(new Object[]{
                HealthCheckRequest.newBuilder().setService("probe").build()});
        return request;
    }

    private void awaitInflight(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (server.getInflightRequestCount() == expected) {
                return;
            }
            Thread.sleep(20);
        }
        assertEquals(expected, server.getInflightRequestCount(),
                "in-flight count never reached " + expected
                        + " — an orphaned stream channel never got channelInactive");
    }
}