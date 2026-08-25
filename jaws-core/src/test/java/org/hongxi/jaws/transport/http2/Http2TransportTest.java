package org.hongxi.jaws.transport.http2;

import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.DefaultProvider;
import org.hongxi.jaws.rpc.Response;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the HTTP/2 transport: a real {@link Http2Server} and
 * {@link Http2Client} exchange Jaws requests over multiplexed h2c streams.
 *
 * @author shenhongxi
 */
class Http2TransportTest {

    private static final String ECHO_INTERFACE = EchoService.class.getName();
    private static final String PARAM_DESC = "java.lang.String";

    private Http2Server server;
    private Http2Client client;

    @BeforeEach
    void setUp() throws IOException {
        int port = findFreePort();
        URL url = new URL("jaws", "127.0.0.1", port, EchoService.class.getName());
        url.addParameter("serialization", "hessian2");

        // Create a simple echo service implementation
        EchoServiceImpl echoImpl = new EchoServiceImpl();
        DefaultProvider<EchoService> provider =
                new DefaultProvider<>(EchoService.class, url, echoImpl);

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
    void echoSync() {
        Response response = client.request(newRequest("echo", "jaws"));
        assertEquals("jaws", response.getValue());
    }

    @Test
    void exceptionPropagation() {
        // sync invocation surfaces the provider exception at the call site,
        // same semantics as the native netty transport
        RuntimeException ex = assertThrows(RuntimeException.class, () -> {
            Response response = client.request(newRequest("boom", "anything"));
            response.getValue();
        });
        // The provider wraps the original exception in a JawsBizException;
        // the original "boom" RuntimeException is the cause.
        Throwable cause = ex.getCause();
        assertTrue(cause != null && cause.getMessage().contains("boom"));
    }

    @Test
    void concurrentMultiplexing() throws Exception {
        int concurrency = 100;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<CompletableFuture<String>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                String arg = "req-" + i;
                futures.add(CompletableFuture.supplyAsync(
                        () -> (String) client.request(newRequest("echo", arg)).getValue(), pool));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(10, TimeUnit.SECONDS);
            for (int i = 0; i < concurrency; i++) {
                assertEquals("req-" + i, futures.get(i).get());
            }
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void gracefulShutdownDrainsActiveRequests() throws Exception {
        // fire a request, then verify awaitInactiveRequests returns after drain
        client.request(newRequest("echo", "drain"));
        assertEquals(0, server.getActiveRequests().get());
        server.stopAccept();
        server.awaitInactiveRequests(1000);
    }

    @Test
    void multiConnectionClient() throws Exception {
        // Create a new client with 3 connections
        int port = findFreePort();
        URL url = new URL("jaws", "127.0.0.1", port, EchoService.class.getName());
        url.addParameter("serialization", "hessian2");
        url.addParameter("connections", "3");

        EchoServiceImpl echoImpl = new EchoServiceImpl();
        DefaultProvider<EchoService> provider =
                new DefaultProvider<>(EchoService.class, url, echoImpl);
        ProviderMessageHandler handler = new ProviderMessageHandler();
        handler.addProvider(provider);

        Http2Server multiServer = new Http2Server(url, handler);
        assertTrue(multiServer.open());

        Http2Client multiClient = new Http2Client(url);
        assertTrue(multiClient.open());

        try {
            // Send multiple concurrent requests - they should be distributed across connections
            int concurrency = 50;
            ExecutorService pool = Executors.newFixedThreadPool(10);
            try {
                List<CompletableFuture<String>> futures = new ArrayList<>();
                for (int i = 0; i < concurrency; i++) {
                    String arg = "multi-" + i;
                    futures.add(CompletableFuture.supplyAsync(
                            () -> (String) multiClient.request(newRequest("echo", arg)).getValue(), pool));
                }
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                        .get(10, TimeUnit.SECONDS);
                for (int i = 0; i < concurrency; i++) {
                    assertEquals("multi-" + i, futures.get(i).get());
                }
            } finally {
                pool.shutdown();
            }
        } finally {
            multiClient.close();
            multiServer.close();
        }
    }

    private DefaultRequest newRequest(String method, String arg) {
        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName(ECHO_INTERFACE);
        request.setMethodName(method);
        request.setParamDesc(PARAM_DESC);
        request.setArguments(new Object[]{arg});
        return request;
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * Simple echo service for testing.
     */
    public interface EchoService {
        String echo(String message);
        String boom(String message);
    }

    public static class EchoServiceImpl implements EchoService {
        @Override
        public String echo(String message) {
            return message;
        }

        @Override
        public String boom(String message) {
            throw new RuntimeException("boom");
        }
    }
}
