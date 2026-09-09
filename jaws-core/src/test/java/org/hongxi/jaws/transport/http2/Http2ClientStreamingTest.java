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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test for HTTP/2 client streaming: client streams request items,
 * server collects them and returns a single response.
 */
class Http2ClientStreamingTest {

    private static final String INTERFACE = ClientStreamService.class.getName();

    private Http2Server server;
    private Http2Client client;

    @BeforeEach
    void setUp() throws IOException {
        int port = findFreePort();
        URL url = new URL("jaws", "127.0.0.1", port, ClientStreamService.class.getName());
        url.addParameter("serialization", "hessian2");

        ClientStreamServiceImpl impl = new ClientStreamServiceImpl();
        DefaultProvider<ClientStreamService> provider =
                new DefaultProvider<>(ClientStreamService.class, url, impl);

        ProviderMessageHandler handler = new ProviderMessageHandler();
        handler.addProvider(provider);

        server = new Http2Server(url, handler);
        assertTrue(server.open());
        client = new Http2Client(url);
        assertTrue(client.open());
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
        if (server != null) server.close();
    }

    @Test
    void clientStreamingCollectsItems() throws Exception {
        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName(INTERFACE);
        request.setMethodName("collectNames");
        request.setParamDesc("java.util.concurrent.Flow$Publisher");
        request.setArguments(new Object[]{});
        // CRITICAL: set the streaming header so the server knows this is client-streaming
        request.setAttachment(Http2Constants.HEADER_STREAMING, StreamType.CLIENT.getValue());

        // Create a request stream publisher
        SubmissionPublisher<Object> requestStream = new SubmissionPublisher<>();

        // Call the client-streaming method
        Flow.Publisher<Object> responsePublisher = client.requestStream(request, requestStream);

        // Subscribe to get the single response value
        CompletableFuture<Object> resultFuture = new CompletableFuture<>();
        responsePublisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;
            @Override
            public void onSubscribe(Flow.Subscription s) {
                this.subscription = s;
                s.request(1);
            }
            @Override
            public void onNext(Object item) {
                resultFuture.complete(item);
            }
            @Override
            public void onError(Throwable throwable) {
                resultFuture.completeExceptionally(throwable);
            }
            @Override
            public void onComplete() {
                resultFuture.complete(null);
            }
        });

        // Send items with small delays
        Thread.sleep(200);
        requestStream.submit("alice");
        Thread.sleep(50);
        requestStream.submit("bob");
        Thread.sleep(50);
        requestStream.submit("charlie");
        Thread.sleep(50);
        requestStream.close();

        // Wait for the result
        Object result = resultFuture.get(10, TimeUnit.SECONDS);
        System.out.println("Client streaming result: " + result);
        assertNotNull(result);
        assertTrue(result.toString().contains("alice"));
        assertTrue(result.toString().contains("bob"));
        assertTrue(result.toString().contains("charlie"));
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // ---- Service interface and implementation ----------------------------

    public interface ClientStreamService {
        String collectNames(Flow.Publisher<String> names);
    }

    public static class ClientStreamServiceImpl implements ClientStreamService {
        @Override
        public String collectNames(Flow.Publisher<String> names) {
            System.out.println("[Server] collectNames called");
            CompletableFuture<String> future = new CompletableFuture<>();
            List<String> collected = new ArrayList<>();

            names.subscribe(new Flow.Subscriber<>() {
                private Flow.Subscription subscription;

                @Override
                public void onSubscribe(Flow.Subscription s) {
                    this.subscription = s;
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(String name) {
                    System.out.println("[Server] received: " + name);
                    collected.add(name);
                }

                @Override
                public void onError(Throwable throwable) {
                    System.err.println("[Server] stream error: " + throwable.getMessage());
                    future.completeExceptionally(throwable);
                }

                @Override
                public void onComplete() {
                    System.out.println("[Server] stream completed. names=" + collected);
                    future.complete("Hello, " + String.join(" & ", collected) + "!");
                }
            });

            try {
                return future.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException("collectNames failed", e);
            }
        }
    }
}
