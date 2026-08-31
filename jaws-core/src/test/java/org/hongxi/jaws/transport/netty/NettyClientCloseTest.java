package org.hongxi.jaws.transport.netty;

import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.DefaultResponseFuture;
import org.hongxi.jaws.rpc.ResponseFuture;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression check: closing a NettyClient must fail pending futures immediately
 * instead of leaving callers blocked until request timeout, and graceful
 * close(int) must drain in-flight requests before tearing down the connection.
 */
class NettyClientCloseTest {

    @Test
    void closeFailsPendingFuturesImmediately() {
        NettyClient client = newNettyClient();
        ResponseFuture future = newPendingFuture(client, 1L);
        client.registerCallback(1L, future);

        client.close();

        assertTrue(future.isDone(), "pending future must be completed on close");
        assertFalse(future.isSuccess());
        assertNotNull(future.getException());
    }

    @Test
    void gracefulCloseWaitsForInFlightRequests() {
        NettyClient client = newNettyClient();
        ResponseFuture future = newPendingFuture(client, 2L);
        client.registerCallback(2L, future);

        // Complete the in-flight request shortly after close begins
        Thread completer = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
            client.removeCallback(2L);
        });
        completer.start();

        long start = System.currentTimeMillis();
        client.close(5000);
        long elapsed = System.currentTimeMillis() - start;

        // Drain finished via removeCallback well before the full timeout
        assertTrue(elapsed < 2000, "graceful close should return once in-flight requests drain");
        assertFalse(future.isDone(), "request drained before close must not be canceled");
        completer.interrupt();
    }

    private NettyClient newNettyClient() {
        Map<String, String> params = new HashMap<>();
        params.put("codec", "jaws");
        return new NettyClient(new URL("jaws", "127.0.0.1", 18003, "test", params));
    }

    private ResponseFuture newPendingFuture(NettyClient client, long requestId) {
        DefaultRequest request = new DefaultRequest();
        request.setRequestId(requestId);
        request.setInterfaceName("org.hongxi.jaws.FooService");
        request.setMethodName("hello");
        request.setParamDesc("");
        return new DefaultResponseFuture(request, 10000, client.getUrl());
    }
}
