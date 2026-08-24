package org.hongxi.jaws.transport.http2;

import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.MessageHandler;
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

    private static final String ECHO_INTERFACE = "org.hongxi.jaws.test.EchoService";
    private static final String PARAM_DESC = "Ljava/lang/String;";

    private Http2Server server;
    private Http2Client client;

    @BeforeEach
    void setUp() throws IOException {
        int port = findFreePort();
        URL url = new URL("jaws", "127.0.0.1", port, "");
        url.addParameter("serialization", "hessian2");

        MessageHandler handler = (channel, message) -> {
            DefaultRequest request = (DefaultRequest) message;
            if ("boom".equals(request.getMethodName())) {
                return CompletableFuture.failedFuture(new RuntimeException("boom"));
            }
            return CompletableFuture.completedFuture(request.getArguments()[0]);
        };

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
        assertTrue(ex.getMessage().contains("boom"));
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
}
