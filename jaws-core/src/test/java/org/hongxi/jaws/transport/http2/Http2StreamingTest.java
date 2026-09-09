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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for HTTP/2 server streaming: the client opens a streaming
 * request and receives multiple items via a {@link StreamSource}.
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
        StreamSource<Object> source = client.requestStream(request, null);

        List<Object> items = collectItems(source, 5, TimeUnit.SECONDS);
        assertEquals(5, items.size());
        assertEquals("hello-0", items.get(0));
        assertEquals("hello-1", items.get(1));
        assertEquals("hello-4", items.get(4));
    }

    @Test
    void serverStreamingEmptyPublisher() throws Exception {
        DefaultRequest request = newRequest("emptyStream", "ignored");
        StreamSource<Object> source = client.requestStream(request, null);

        List<Object> items = collectItems(source, 5, TimeUnit.SECONDS);
        assertTrue(items.isEmpty(), "empty stream should produce no items");
    }

    @Test
    void serverStreamingSingleItem() throws Exception {
        DefaultRequest request = newRequest("singleItemStream", "only");
        StreamSource<Object> source = client.requestStream(request, null);

        List<Object> items = collectItems(source, 5, TimeUnit.SECONDS);
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
     * Subscribe to the source and collect all items until onCompleted.
     */
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

    public interface StreamService {
        StreamSource<String> serverStream(String prefix);

        StreamSource<String> emptyStream(String ignored);

        StreamSource<String> singleItemStream(String item);
    }

    public static class StreamServiceImpl implements StreamService {
        @Override
        public StreamSource<String> serverStream(String prefix) {
            StreamSubject<String> observer = new StreamSubject<>();
            for (int i = 0; i < 5; i++) {
                observer.onNext(prefix + "-" + i);
            }
            observer.onCompleted();
            return observer;
        }

        @Override
        public StreamSource<String> emptyStream(String ignored) {
            StreamSubject<String> observer = new StreamSubject<>();
            observer.onCompleted();
            return observer;
        }

        @Override
        public StreamSource<String> singleItemStream(String item) {
            StreamSubject<String> observer = new StreamSubject<>();
            observer.onNext(item);
            observer.onCompleted();
            return observer;
        }

    }
}
