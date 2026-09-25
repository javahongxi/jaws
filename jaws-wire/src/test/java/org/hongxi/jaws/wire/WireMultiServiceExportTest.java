package org.hongxi.jaws.wire;

import org.hongxi.jaws.rpc.DefaultProvider;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Exporter;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;
import org.hongxi.jaws.transport.StreamSubject;
import org.hongxi.jaws.wire.health.HealthCheckRequest;
import org.hongxi.jaws.wire.health.HealthCheckResponse;
import org.hongxi.jaws.wire.health.HealthCheckResponse.ServingStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for exporting <b>more than one</b> service on the same wire port
 * through the Provider pipeline ({@link WireProtocol} / {@link WireExporter}).
 * <p>
 * The transport layer deliberately shares one server per {@code host:port}, so
 * the second export does not get its own server — it has to be served by the
 * handler the first export installed. That only works if the protobuf type
 * lookup is per port rather than per export: a per-export lookup table answers
 * "no method info registered" for every service but the first, which silently
 * makes a whole host uncallable while its provider is registered, its
 * reflection entry present, and its port listening.
 *
 * @author shenhongxi
 */
class WireMultiServiceExportTest {

    /**
     * Service name used on the wire path. Deliberately not a real proto name:
     * these services are found by the method-name join the pipeline falls back
     * to, and {@code grpc.health.v1.Health} is now claimed by the built-in
     * health service on this port.
     */
    private static final String SERVICE = "demo.Multi";

    private static final long CALL_TIMEOUT_SECONDS = 5;

    /** First service on the port: unary only. */
    public interface Alpha {
        HealthCheckResponse ping(HealthCheckRequest request);
    }

    /** Second service on the same port: unary plus a server stream. */
    public interface Beta {
        HealthCheckResponse pong(HealthCheckRequest request);

        StreamSource<HealthCheckResponse> watch(HealthCheckRequest request);
    }

    public static class AlphaImpl implements Alpha {
        @Override
        public HealthCheckResponse ping(HealthCheckRequest request) {
            return HealthCheckResponse.newBuilder().setStatus(ServingStatus.SERVING).build();
        }
    }

    public static class BetaImpl implements Beta {
        @Override
        public HealthCheckResponse pong(HealthCheckRequest request) {
            return HealthCheckResponse.newBuilder().setStatus(ServingStatus.NOT_SERVING).build();
        }

        @Override
        public StreamSource<HealthCheckResponse> watch(HealthCheckRequest request) {
            StreamSubject<HealthCheckResponse> out = new StreamSubject<>();
            out.onNext(HealthCheckResponse.newBuilder().setStatus(ServingStatus.SERVING).build());
            out.onNext(HealthCheckResponse.newBuilder().setStatus(ServingStatus.UNKNOWN).build());
            out.onCompleted();
            return out;
        }
    }

    private final WireProtocol protocol = new WireProtocol();
    private final List<URL> exported = new ArrayList<>();
    private WireClient client;

    @AfterEach
    void tearDown() {
        for (URL url : exported) {
            protocol.unexport(url);
        }
        exported.clear();
        if (client != null) {
            client.close();
            client = null;
        }
    }

    @Test
    void secondServiceOnTheSamePortIsReachable() throws Exception {
        int port = freePort();
        export(port, Alpha.class, new AlphaImpl());
        export(port, Beta.class, new BetaImpl());
        openClient(port);

        assertEquals(ServingStatus.SERVING, statusOf(unary("Ping", 1L)),
                "the first service must answer");
        assertEquals(ServingStatus.NOT_SERVING, statusOf(unary("Pong", 2L)),
                "the second service on the same port must answer too");
    }

    @Test
    void secondServiceServerStreamIsReachable() throws Exception {
        int port = freePort();
        export(port, Alpha.class, new AlphaImpl());
        export(port, Beta.class, new BetaImpl());
        openClient(port);

        List<HealthCheckResponse> replies = serverStream("Watch", 3L);

        assertEquals(2, replies.size(), "the second service's stream must flow");
        assertEquals(ServingStatus.SERVING, replies.get(0).getStatus());
        assertEquals(ServingStatus.UNKNOWN, replies.get(1).getStatus());
    }

    @Test
    void unexportingTheSecondServiceKeepsTheFirstReachable() throws Exception {
        int port = freePort();
        URL alphaUrl = export(port, Alpha.class, new AlphaImpl());
        URL betaUrl = export(port, Beta.class, new BetaImpl());
        openClient(port);

        assertEquals(ServingStatus.NOT_SERVING, statusOf(unary("Pong", 4L)));

        protocol.unexport(betaUrl);

        assertEquals(ServingStatus.SERVING, statusOf(unary("Ping", 5L)),
                "removing one service must not take the shared handler down");
    }

    // ------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------

    private <T> URL export(int port, Class<T> interfaceClass, T impl) {
        URL url = new URL("wire", "127.0.0.1", port, interfaceClass.getName());
        Exporter<?> exporter = protocol.export(
                new DefaultProvider<>(interfaceClass, url, impl));
        assertTrue(exporter.isAvailable(), "exporter should be available: " + url);
        exported.add(url);
        return url;
    }

    private void openClient(int port) {
        client = new WireClient(new URL("wire", "127.0.0.1", port, ""));
        assertTrue(client.open(), "wire client should connect on port " + port);
    }

    private DefaultRequest request(String method, long requestId) {
        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName(SERVICE);
        request.setMethodName(method);
        request.setRequestId(requestId);
        request.setArguments(new Object[]{
                HealthCheckRequest.newBuilder().setService("probe").build()});
        return request;
    }

    private Response unary(String method, long requestId) {
        return client.request(request(method, requestId), HealthCheckResponse.parser());
    }

    private ServingStatus statusOf(Response response) {
        return ((HealthCheckResponse) response.getValue()).getStatus();
    }

    private List<HealthCheckResponse> serverStream(String method, long requestId)
            throws InterruptedException {
        StreamSource<Object> source =
                client.requestStream(request(method, requestId), HealthCheckResponse.parser());
        List<HealthCheckResponse> replies = new CopyOnWriteArrayList<>();
        CountDownLatch finished = new CountDownLatch(1);
        source.subscribe(new StreamObserver<>() {
            @Override
            public void onNext(Object item) {
                replies.add((HealthCheckResponse) item);
            }

            @Override
            public void onError(Throwable throwable) {
                finished.countDown();
            }

            @Override
            public void onCompleted() {
                finished.countDown();
            }
        });
        assertTrue(finished.await(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "server stream should terminate");
        return replies;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
