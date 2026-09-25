package org.hongxi.jaws.wire;

import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.wire.health.HealthCheckRequest;
import org.hongxi.jaws.wire.health.HealthCheckResponse;
import org.hongxi.jaws.wire.health.HealthCheckResponse.ServingStatus;
import org.hongxi.jaws.wire.reflection.ServerReflectionRequest;
import org.hongxi.jaws.wire.reflection.ServiceResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the built-in {@code grpc.health.v1.Health} service in
 * <b>Provider pipeline mode</b> — the mode where a service is exported through
 * the Jaws pipeline rather than registered into a {@link WireHandlerRegistry}.
 * <p>
 * Health used to be answered by a hand-rolled branch inside
 * {@code ProviderCallDispatcher} that re-implemented the unary round trip
 * (decode, trace, headers, DATA, trailers) and knew only {@code Check}. The
 * point of these tests is that health is now the <em>same</em> handler object
 * Direct API mode uses: {@code Watch} works, reflection advertises the
 * service, and the NOT_FOUND semantics survive the move.
 * <p>
 * Advertising matters because the project already ruled under-reporting
 * {@code grpc.*} a defect (see {@link WireReflectionServiceTest}): a server
 * that answers {@code Check} but hides the service leaves reflection-driven
 * tools such as {@code grpcurl} unable to reach it.
 *
 * @author shenhongxi
 */
class WirePipelineHealthTest {

    private static final String HEALTH = "grpc.health.v1.Health";

    /** Business handler that refuses everything: these tests never reach it. */
    private static final MessageHandler UNTOUCHABLE = new MessageHandler() {
        @Override
        public java.util.concurrent.CompletableFuture<Object> handleAsync(Object message) {
            throw new IllegalStateException("business pipeline must not serve " + HEALTH);
        }

        @Override
        public StreamSource<Object> handleStream(Request request, StreamSource<Object> in) {
            throw new IllegalStateException("business pipeline must not stream " + HEALTH);
        }
    };

    private WireServer server;
    private WireClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
            client = null;
        }
        if (server != null) {
            server.close();
            server = null;
        }
    }

    @Test
    void checkIsAnsweredByTheBuiltInHandler() throws Exception {
        startPipelineServer();

        HealthCheckResponse reply = check("");

        assertEquals(ServingStatus.SERVING, reply.getStatus(),
                "the overall server status serves by default");
    }

    @Test
    void unknownServiceStillReportsNotFound() throws Exception {
        startPipelineServer();

        WireStatusException failure = assertThrows(WireStatusException.class,
                () -> check("no.such.Service"));

        assertEquals(WireConstants.STATUS_NOT_FOUND, failure.getGrpcStatus(),
                "Check of an unknown service must keep failing with NOT_FOUND");
    }

    @Test
    void watchStreamsTheCurrentStatusAndEveryChange() throws Exception {
        startPipelineServer();

        List<ServingStatus> seen = new CopyOnWriteArrayList<>();
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(2);
        StreamSource<Object> watch = client.requestStream(
                healthRequest("Watch", "svc.A"), HealthCheckResponse.parser());
        watch.subscribe(new StreamObserver<>() {
            @Override
            public void onNext(Object item) {
                seen.add(((HealthCheckResponse) item).getStatus());
                first.countDown();
                second.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onCompleted() {
            }
        });

        // The service is unknown until told otherwise, so Watch opens with
        // SERVICE_UNKNOWN per the protocol spec.
        assertTrue(first.await(5, TimeUnit.SECONDS),
                "Watch should emit the current status, got " + seen);
        assertEquals(ServingStatus.SERVICE_UNKNOWN, seen.get(0));

        // Only then flip the status: the change must arrive on the same stream.
        server.getHealthService().setStatus("svc.A", ServingStatus.NOT_SERVING);

        assertTrue(second.await(5, TimeUnit.SECONDS),
                "Watch should report the change, got " + seen);
        assertEquals(ServingStatus.NOT_SERVING, seen.get(1));
    }

    @Test
    void reflectionAdvertisesTheHealthService() throws Exception {
        startPipelineServer();

        Set<String> listed = server.getReflectionService()
                .handleRequest(ServerReflectionRequest.newBuilder()
                        .setListServices("").build())
                .getListServicesResponse().getServiceList().stream()
                .map(ServiceResponse::getName)
                .collect(Collectors.toSet());

        assertTrue(listed.contains(HEALTH),
                "a server that answers Check must also advertise it, got " + listed);
    }

    // ------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------

    private void startPipelineServer() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        server = new WireServer(new URL("wire", "127.0.0.1", port, ""), UNTOUCHABLE);
        assertTrue(server.open(), "wire server should bind port " + port);
        client = new WireClient(new URL("wire", "127.0.0.1", port, ""));
        assertTrue(client.open(), "wire client should connect");
    }

    private DefaultRequest healthRequest(String method, String service) {
        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName(HEALTH);
        request.setMethodName(method);
        request.setRequestId(System.nanoTime());
        request.setArguments(new Object[]{
                HealthCheckRequest.newBuilder().setService(service).build()});
        return request;
    }

    private HealthCheckResponse check(String service) {
        Response response = client.request(healthRequest("Check", service),
                HealthCheckResponse.parser());
        return (HealthCheckResponse) response.getValue();
    }
}
