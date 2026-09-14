package org.hongxi.jaws.transport.http2;

import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.DefaultProvider;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.RpcContext;
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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void gracefulShutdownDrainsInflightRequests() throws Exception {
        // fire a request, then verify drainInflightRequests returns after drain
        client.request(newRequest("echo", "drain"));
        assertEquals(0, server.getInflightRequestCount());
        server.stopAccept();
        server.drainInflightRequests(1000);
    }

    @Test
    void unaryResponseDestroysRpcContextOnBusinessThread() throws Exception {
        // RpcContext.init() runs on the business-executor thread inside
        // Http2StreamServerHandler.dispatch(); destroy() must pair with it on
        // that same thread.  The inflight counter is released in the Netty
        // write listener, which fires on the event-loop thread — a destroy()
        // moved into that listener silently no-ops there (ThreadLocal.remove
        // is per-thread) and leaves the stale context pinned on the pooled
        // business thread.
        //
        // The probe rides the same executor: with a single-worker pool and a
        // FIFO queue, the probe task runs on the echo's own thread strictly
        // after the request task returns, so getContext() observed inside the
        // probe is that thread's authoritative state post-destroy.
        int port = findFreePort();
        URL url = new URL("jaws", "127.0.0.1", port, EchoService.class.getName());
        url.addParameter("serialization", "hessian2");
        url.addParameter("minWorkerThreads", "1");
        url.addParameter("maxWorkerThreads", "1");

        DefaultProvider<EchoService> provider =
                new DefaultProvider<>(EchoService.class, url, new EchoServiceImpl());
        ProviderMessageHandler handler = new ProviderMessageHandler();
        handler.addProvider(provider);

        Http2Server probeServer = new Http2Server(url, handler);
        Http2Client probeClient = new Http2Client(url);
        try {
            assertTrue(probeServer.open());
            assertTrue(probeClient.open());

            String marker = "ctx-leak-" + System.nanoTime();
            DefaultRequest request = newRequest("echo", marker);
            request.setAttachment("ctx-marker", marker);

            AtomicReference<RpcContext> contextAtInvoke = new AtomicReference<>();
            AtomicReference<Thread> threadAtInvoke = new AtomicReference<>();
            EchoServiceImpl.CAPTURE_HOOK = (ctx, thread) -> {
                contextAtInvoke.set(ctx);
                threadAtInvoke.set(thread);
            };
            try {
                Response response = probeClient.request(request);
                assertEquals(marker, response.getValue());
                assertNotNull(contextAtInvoke.get(),
                        "hook must observe the initialized RpcContext at invocation");

                // The client has returned, so the response frame was written;
                // the request task (which ends with destroy) may still be a few
                // instructions from returning.  The probe queues behind it.
                Future<Object> probe = probeServer.serverExecutor().submit(() -> {
                    Thread current = Thread.currentThread();
                    if (current != threadAtInvoke.get()) {
                        return "probe ran on " + current.getName()
                                + ", not the business thread " + threadAtInvoke.get().getName();
                    }
                    // Fresh context after destroy() means the entry was removed
                    // (withInitial fabricates a new instance); identity equality
                    // against the captured instance is the authoritative probe.
                    if (RpcContext.getContext() == contextAtInvoke.get()) {
                        return "RpcContext of the finished unary call was never destroyed"
                                + " on the business thread — destroy() ran on the wrong"
                                + " thread (event-loop write listener), leaking the"
                                + " ThreadLocal entry";
                    }
                    return null;
                });
                Object verdict = probe.get(5, TimeUnit.SECONDS);
                assertTrue(verdict == null, String.valueOf(verdict));
            } finally {
                EchoServiceImpl.CAPTURE_HOOK = null;
            }
        } finally {
            probeClient.close();
            probeServer.close();
        }
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
        /**
         * Set by {@link #unaryResponseDestroysRpcContextOnBusinessThread()} to
         * capture the invoking business thread and its live RpcContext instance.
         */
        static volatile BiConsumer<RpcContext, Thread> CAPTURE_HOOK;

        @Override
        public String echo(String message) {
            BiConsumer<RpcContext, Thread> hook = CAPTURE_HOOK;
            if (hook != null) {
                hook.accept(RpcContext.getContext(), Thread.currentThread());
            }
            return message;
        }

        @Override
        public String boom(String message) {
            throw new RuntimeException("boom");
        }
    }
}
