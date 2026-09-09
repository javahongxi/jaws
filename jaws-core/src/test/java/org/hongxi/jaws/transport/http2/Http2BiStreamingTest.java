package org.hongxi.jaws.transport.http2;

import org.hongxi.jaws.rpc.DefaultProvider;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.StreamSubject;
import org.hongxi.jaws.transport.ProviderMessageHandler;
import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for HTTP/2 bidirectional streaming: the client sends a
 * stream of request items and receives a stream of response items concurrently.
 *
 * @author shenhongxi
 */
class Http2BiStreamingTest {

    private static final String BIDI_INTERFACE = BidiService.class.getName();

    private Http2Server server;
    private Http2Client client;

    @BeforeEach
    void setUp() throws IOException {
        int port = findFreePort();
        URL url = new URL("jaws", "127.0.0.1", port, BidiService.class.getName());
        url.addParameter("serialization", "hessian2");

        BidiServiceImpl impl = new BidiServiceImpl();
        DefaultProvider<BidiService> provider =
                new DefaultProvider<>(BidiService.class, url, impl);

        ProviderMessageHandler handler = new ProviderMessageHandler();
        handler.addProvider(provider);

        server = new Http2Server(url, handler);
        assertTrue(server.open());
        client = new Http2Client(url);
        assertTrue(client.open());
    }

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
    void bidiStreamingEchoReceivesAllItems() throws Exception {
        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName(BIDI_INTERFACE);
        request.setMethodName("echo");

        // Create a request stream observer that buffers items until subscription
        StreamSubject<Object> requestObserver = new StreamSubject<>();

        StreamSource<Object> responseSource = client.requestStream(request, requestObserver);

        // Wait a bit for the subscription to be registered
        Thread.sleep(200);

        // Send request items with delays to avoid race conditions
        requestObserver.onNext("hello");
        Thread.sleep(50);
        requestObserver.onNext("world");
        Thread.sleep(50);
        requestObserver.onNext("bidi");
        Thread.sleep(50);
        requestObserver.onCompleted();

        List<Object> items = collectItems(responseSource, 5, TimeUnit.SECONDS);
        assertEquals(3, items.size());
        assertEquals("echo:hello", items.get(0));
        assertEquals("echo:world", items.get(1));
        assertEquals("echo:bidi", items.get(2));
    }

    @Test
    void bidiStreamingEmptyRequestStream() throws Exception {
        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName(BIDI_INTERFACE);
        request.setMethodName("echo");

        StreamSubject<Object> requestObserver = new StreamSubject<>();
        StreamSource<Object> responseSource = client.requestStream(request, requestObserver);

        // Wait for subscription, then complete immediately
        Thread.sleep(100);
        requestObserver.onCompleted();

        List<Object> items = collectItems(responseSource, 5, TimeUnit.SECONDS);
        assertTrue(items.isEmpty(), "empty request stream should produce no response items");
    }

    // ---- helpers --------------------------------------------------------

    private List<Object> collectItems(StreamSource<Object> source,
                                      long timeout, TimeUnit unit) throws Exception {
        List<Object> items = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        source.subscribe(new StreamObserver<>() {
            @Override
            public void onNext(Object item) {
                items.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                errorRef.set(throwable);
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        });

        assertTrue(latch.await(timeout, unit), "timed out waiting for stream to complete");
        Throwable error = errorRef.get();
        if (error != null) {
            throw new RuntimeException("Stream observer got error", error);
        }
        return items;
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // ---- Service interface and implementation ----------------------------

    public interface BidiService {
        StreamSource<String> echo(StreamObserver<String> requests);
    }

    public static class BidiServiceImpl implements BidiService {
        @Override
        public StreamSource<String> echo(StreamObserver<String> requests) {
            StreamSubject<String> responseObserver = new StreamSubject<>();

            // Subscribe to the request stream
            if (requests instanceof StreamSource<?> source) {
                @SuppressWarnings("unchecked")
                StreamSource<String> requestSource = (StreamSource<String>) source;
                requestSource.subscribe(new StreamObserver<>() {
                    @Override
                    public void onNext(String item) {
                        responseObserver.onNext("echo:" + item);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        responseObserver.onError(throwable);
                    }

                    @Override
                    public void onCompleted() {
                        responseObserver.onCompleted();
                    }
                });
            }

            return responseObserver;
        }
    }
}
