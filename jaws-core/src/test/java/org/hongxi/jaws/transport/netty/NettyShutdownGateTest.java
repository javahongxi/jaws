package org.hongxi.jaws.transport.netty;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.exception.JawsErrorCode;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultProvider;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.ProviderMessageHandler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check of the shutdown gate on the jaws binary protocol, which is
 * the path where {@code stopAccept()} has no GOAWAY to lean on: closing the
 * listening socket leaves this connection untouched, so the only thing standing
 * between "draining" and "still serving traffic forever" is the refusal below.
 * <p>
 * Three links are pinned in one run: the server refuses with
 * {@code SERVICE_SHUTDOWN} before dispatching, that code survives the wire and
 * reaches the caller as itself (not degraded to the generic
 * {@code SERVICE_REJECT} a full pool answers with), and the client treats it as
 * "this connection is finished" so the next call reconnects elsewhere.
 *
 * @author shenhongxi
 */
class NettyShutdownGateTest {

    private static final String PARAM_DESC = "java.lang.String";

    public interface EchoService {
        String echo(String message);
    }

    public static class EchoServiceImpl implements EchoService {
        @Override
        public String echo(String message) {
            return message;
        }
    }

    @Test
    void refusalAfterStopAcceptReachesTheClientAsShutdownAndDropsTheConnection() throws Exception {
        int port = findFreePort();
        URL url = new URL("jaws", "127.0.0.1", port, EchoService.class.getName());
        url.addParameter(UrlParam.Transport.SERIALIZATION.getName(), "hessian2");

        DefaultProvider<EchoService> provider =
                new DefaultProvider<>(EchoService.class, url, new EchoServiceImpl());
        ProviderMessageHandler handler = new ProviderMessageHandler();
        handler.addProvider(provider);

        NettyServer server = new NettyServer(url, handler);
        assertTrue(server.open(), "server should bind port " + port);
        NettyClient client = new NettyClient(url);
        try {
            assertTrue(client.open(), "client should connect");
            assertEquals("before", client.request(newRequest("before")).getValue());

            server.stopAccept();
            assertFalse(server.isAccepting(), "stopAccept must lower the accept gate");

            // Same connection, opened while the server was still accepting. The
            // binary protocol has no frame telling the client to leave, so this
            // call does reach the server and must be refused there.
            Response refused = client.request(newRequest("after"));
            Throwable refusal = null;
            try {
                refused.getValue();
            } catch (RuntimeException e) {
                refusal = e;
            }

            assertNotNull(refusal, "a refused call must not look like a successful one");
            JawsServiceException shutdown = assertInstanceOf(JawsServiceException.class, refusal);
            assertEquals(JawsErrorCode.SERVICE_SHUTDOWN, shutdown.getErrorCode(),
                    "the refusal must arrive as SERVICE_SHUTDOWN, not a generic reject");
            assertEquals(0, server.getInflightRequestCount(),
                    "a refused request must never join the drain set");

            // The client drops a connection whose peer is shutting down, which is
            // what lets the next call reconnect onto a node still serving
            assertFalse(client.isAvailable(),
                    "the refusal must drop this connection so the next call reconnects");
        } finally {
            client.close();
            server.close();
        }
    }

    /** Control half: while the gate is up the very same call is served. */
    @Test
    void sameRequestIsServedWhileTheGateIsUp() throws Exception {
        int port = findFreePort();
        URL url = new URL("jaws", "127.0.0.1", port, EchoService.class.getName());
        url.addParameter(UrlParam.Transport.SERIALIZATION.getName(), "hessian2");

        DefaultProvider<EchoService> provider =
                new DefaultProvider<>(EchoService.class, url, new EchoServiceImpl());
        ProviderMessageHandler handler = new ProviderMessageHandler();
        handler.addProvider(provider);

        NettyServer server = new NettyServer(url, handler);
        assertTrue(server.open(), "server should bind port " + port);
        NettyClient client = new NettyClient(url);
        try {
            assertTrue(client.open(), "client should connect");
            assertEquals("while-open", client.request(newRequest("while-open")).getValue());
            assertTrue(client.isAvailable(), "a served call must leave the connection up");
        } finally {
            client.close();
            server.close();
        }
    }

    private Request newRequest(String arg) {
        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName(EchoService.class.getName());
        request.setMethodName("echo");
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
