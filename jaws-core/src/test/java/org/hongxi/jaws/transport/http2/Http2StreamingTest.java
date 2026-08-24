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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for HTTP/2 server streaming: the client opens a streaming
 * request and receives multiple items via a {@link Flow.Publisher}.
 *
 * @author shenhongxi
 */
class Http2StreamingTest {

    private static final String STREAM_INTERFACE = StreamService.class.getName();
    private static final String STRING_PARAM_DESC = "java.lang.String";

    private Http2Server server;
    private Http2Client client;

    @BeforeEach
    void setUp() throws IOException {
        int port = findFreePort();
        URL url = new URL("jaws", "127.0.0.1", port, StreamService.class.getName());
        url.addParameter("serialization", "hessian2");

        StreamServiceImpl impl = new StreamServiceImpl();
        DefaultProvider<StreamService> provider =
                new DefaultProvider<>(StreamService.class, url, impl);

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
    void serverStreamingReceivesAllItems() throws Exception {
        DefaultRequest request = newRequest("serverStream", "hello");
        Flow.Publisher<Object> publisher = client.requestStream(request);

        List<Object> items = collectItems(publisher, 5, TimeUnit.SECONDS);
        assertEquals(5, items.size());
        assertEquals("hello-0", items.get(0));
        assertEquals("hello-1", items.get(1));
        assertEquals("hello-4", items.get(4));
    }

    @Test
    void serverStreamingEmptyPublisher() throws Exception {
        DefaultRequest request = newRequest("emptyStream", "ignored");
        Flow.Publisher<Object> publisher = client.requestStream(request);

        List<Object> items = collectItems(publisher, 5, TimeUnit.SECONDS);
        assertTrue(items.isEmpty(), "empty stream should produce no items");
    }

    @Test
    void serverStreamingSingleItem() throws Exception {
        DefaultRequest request = newRequest("singleItemStream", "only");
        Flow.Publisher<Object> publisher = client.requestStream(request);

        List<Object> items = collectItems(publisher, 5, TimeUnit.SECONDS);
        assertEquals(1, items.size());
        assertEquals("only", items.get(0));
    }

    // ---- helpers --------------------------------------------------------

    private DefaultRequest newRequest(String method, String arg) {
        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName(STREAM_INTERFACE);
        request.setMethodName(method);
        request.setParamDesc(STRING_PARAM_DESC);
        request.setArguments(new Object[]{arg});
        return request;
    }

    /**
     * Subscribe to the publisher and collect all items until onComplete.
     */
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

    public interface StreamService {
        Flow.Publisher<String> serverStream(String prefix);

        Flow.Publisher<String> emptyStream(String ignored);

        Flow.Publisher<String> singleItemStream(String item);
    }

    public static class StreamServiceImpl implements StreamService {
        @Override
        public Flow.Publisher<String> serverStream(String prefix) {
            return subscriber -> {
                subscriber.onSubscribe(new Flow.Subscription() {
                    private int count = 0;

                    @Override
                    public void request(long n) {
                        for (long i = 0; i < n && count < 5; i++, count++) {
                            subscriber.onNext(prefix + "-" + count);
                        }
                        if (count >= 5) {
                            subscriber.onComplete();
                        }
                    }

                    @Override
                    public void cancel() {
                    }
                });
            };
        }

        @Override
        public Flow.Publisher<String> emptyStream(String ignored) {
            return subscriber -> {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                        subscriber.onComplete();
                    }

                    @Override
                    public void cancel() {
                    }
                });
            };
        }

        @Override
        public Flow.Publisher<String> singleItemStream(String item) {
            return subscriber -> {
                subscriber.onSubscribe(new Flow.Subscription() {
                    private boolean sent = false;

                    @Override
                    public void request(long n) {
                        if (!sent) {
                            sent = true;
                            subscriber.onNext(item);
                            subscriber.onComplete();
                        }
                    }

                    @Override
                    public void cancel() {
                    }
                });
            };
        }

    }
}
