package org.hongxi.jaws.transport.http2;

import org.hongxi.jaws.rpc.DefaultProvider;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.ProviderMessageHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
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

        // Create a request stream publisher that buffers items until subscription
        BufferedPublisher<Object> requestPublisher = new BufferedPublisher<>();

        Flow.Publisher<Object> responsePublisher = client.requestBiStream(request, requestPublisher);

        // Wait a bit for the subscription to be registered
        Thread.sleep(200);

        // Send request items with delays to avoid race conditions
        requestPublisher.publish("hello");
        Thread.sleep(50);
        requestPublisher.publish("world");
        Thread.sleep(50);
        requestPublisher.publish("bidi");
        Thread.sleep(50);
        requestPublisher.complete();

        List<Object> items = collectItems(responsePublisher, 5, TimeUnit.SECONDS);
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

        BufferedPublisher<Object> requestPublisher = new BufferedPublisher<>();
        Flow.Publisher<Object> responsePublisher = client.requestBiStream(request, requestPublisher);

        // Wait for subscription, then complete immediately
        Thread.sleep(100);
        requestPublisher.complete();

        List<Object> items = collectItems(responsePublisher, 5, TimeUnit.SECONDS);
        assertTrue(items.isEmpty(), "empty request stream should produce no response items");
    }

    // ---- helpers --------------------------------------------------------

    private List<Object> collectItems(Flow.Publisher<Object> publisher,
                                      long timeout, TimeUnit unit) throws Exception {
        List<Object> items = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription s) {
                this.subscription = s;
                s.request(Long.MAX_VALUE);
            }

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
            public void onComplete() {
                latch.countDown();
            }
        });

        assertTrue(latch.await(timeout, unit), "timed out waiting for stream to complete");
        Throwable error = errorRef.get();
        if (error != null) {
            throw new RuntimeException("Stream subscriber got error", error);
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
        Flow.Publisher<String> echo(Flow.Publisher<String> requests);
    }

    public static class BidiServiceImpl implements BidiService {
        @Override
        public Flow.Publisher<String> echo(Flow.Publisher<String> requests) {
            BufferedPublisher<String> responsePublisher = new BufferedPublisher<>();

            requests.subscribe(new Flow.Subscriber<>() {
                private Flow.Subscription subscription;

                @Override
                public void onSubscribe(Flow.Subscription s) {
                    this.subscription = s;
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(String item) {
                    responsePublisher.publish("echo:" + item);
                }

                @Override
                public void onError(Throwable throwable) {
                    responsePublisher.completeExceptionally(throwable);
                }

                @Override
                public void onComplete() {
                    responsePublisher.complete();
                }
            });

            return responsePublisher;
        }
    }

    /**
     * A simple Publisher that buffers items until a subscriber subscribes,
     * then delivers all buffered items followed by any new items.
     */
    static class BufferedPublisher<T> implements Flow.Publisher<T> {
        private final List<T> buffer = new ArrayList<>();
        private Flow.Subscriber<? super T> subscriber;
        private boolean completed;
        private Throwable error;

        synchronized void publish(T item) {
            if (completed) return;
            if (subscriber != null) {
                subscriber.onNext(item);
            } else {
                buffer.add(item);
            }
        }

        synchronized void complete() {
            if (completed) return;
            completed = true;
            if (subscriber != null) {
                subscriber.onComplete();
            }
        }

        synchronized void completeExceptionally(Throwable t) {
            if (completed) return;
            completed = true;
            error = t;
            if (subscriber != null) {
                subscriber.onError(t);
            }
        }

        @Override
        public synchronized void subscribe(Flow.Subscriber<? super T> s) {
            this.subscriber = s;
            s.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    // Drain buffered items
                    synchronized (BufferedPublisher.this) {
                        for (T item : buffer) {
                            s.onNext(item);
                        }
                        buffer.clear();
                        if (completed) {
                            if (error != null) {
                                s.onError(error);
                            } else {
                                s.onComplete();
                            }
                        }
                    }
                }

                @Override
                public void cancel() {
                }
            });
        }
    }
}
