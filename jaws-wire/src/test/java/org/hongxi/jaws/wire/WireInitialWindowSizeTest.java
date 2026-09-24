package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import io.netty.channel.Channel;
import io.netty.handler.codec.http2.Http2FrameCodec;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.wire.health.HealthCheckRequest;
import org.hongxi.jaws.wire.health.HealthCheckResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof that both wire endpoints advertise the 8 MiB
 * SETTINGS_INITIAL_WINDOW_SIZE (see {@link WireConstants#INITIAL_WINDOW_SIZE})
 * and that the value is in force after the SETTINGS exchange.
 *
 * @author shenhongxi
 */
class WireInitialWindowSizeTest {

    private static final String SERVICE = "org.hongxi.jaws.wire.WindowProbeService";

    private WireServer server;
    private WireClient client;

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
    void bothEndpointsAdvertiseTheConfiguredStreamWindow() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        WireHandlerRegistry registry = new WireHandlerRegistry();
        registry.register(SERVICE, "SayHello", new WireMethodHandler() {
            @Override
            public HealthCheckResponse handle(Message request) {
                return HealthCheckResponse.newBuilder()
                        .setStatus(HealthCheckResponse.ServingStatus.SERVING).build();
            }

            @Override
            public com.google.protobuf.Parser<? extends Message> getRequestParser() {
                return HealthCheckRequest.parser();
            }
        });
        server = new WireServer(new URL("wire", "0.0.0.0", port, ""), registry);
        assertTrue(server.open());
        client = new ExposedClient(new URL("wire", "127.0.0.1", port, SERVICE));
        assertTrue(client.open());

        // Drive one real call so the SETTINGS exchange has definitely completed
        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName(SERVICE);
        request.setMethodName("SayHello");
        request.setRequestId(1L);
        request.setArguments(new Object[]{HealthCheckRequest.newBuilder()
                .setService("probe").build()});
        Response response = client.request(request, HealthCheckResponse.parser());
        assertEquals(HealthCheckResponse.ServingStatus.SERVING,
                ((HealthCheckResponse) response.getValue()).getStatus());

        Channel connection = awaitConnection((ExposedClient) client, 2000);
        assertNotNull(connection, "client connection channel");
        Http2FrameCodec codec = connection.pipeline().get(Http2FrameCodec.class);
        assertNotNull(codec);
        // Peer's advertised window: what this endpoint may receive per stream
        // Peer's advertised window (governs what we may send per stream):
        // read via the remote flow controller after the SETTINGS exchange
        io.netty.handler.codec.http2.DefaultHttp2RemoteFlowController remoteFc =
                (io.netty.handler.codec.http2.DefaultHttp2RemoteFlowController)
                        codec.connection().remote().flowController();
        Long remoteWindow = awaitRemoteWindow(remoteFc, 2000);
        assertNotNull(remoteWindow, "server SETTINGS must arrive");
        assertEquals((long) WireConstants.INITIAL_WINDOW_SIZE, remoteWindow,
                "server must advertise the 8 MiB stream window");
        // Own advertised window: what the peer may send us per stream
        io.netty.handler.codec.http2.DefaultHttp2LocalFlowController localFc =
                (io.netty.handler.codec.http2.DefaultHttp2LocalFlowController)
                        codec.connection().local().flowController();
        assertEquals((long) WireConstants.INITIAL_WINDOW_SIZE,
                localFc.initialWindowSize(),
                "client must advertise the 8 MiB stream window");
    }

    /** Exposes the protected connection accessor for assertions. */
    private static final class ExposedClient extends WireClient {
        ExposedClient(URL url) {
            super(url);
        }

        Channel connection() {
            return activeChannel();
        }
    }

    private static Channel awaitConnection(ExposedClient client, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Channel ch = client.connection();
            if (ch != null && ch.isActive() && ch.pipeline().get(Http2FrameCodec.class) != null) {
                return ch;
            }
            Thread.sleep(20);
        }
        return null;
    }

    private static Long awaitRemoteWindow(
            io.netty.handler.codec.http2.DefaultHttp2RemoteFlowController remoteFc,
            long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (remoteFc.initialWindowSize() == WireConstants.INITIAL_WINDOW_SIZE) {
                return (long) remoteFc.initialWindowSize();
            }
            Thread.sleep(20);
        }
        return (long) remoteFc.initialWindowSize();
    }
}
