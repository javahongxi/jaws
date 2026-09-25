package org.hongxi.jaws.wire;

import org.hongxi.jaws.wire.reflection.ServerReflectionRequest;
import org.hongxi.jaws.wire.reflection.ServerReflectionResponse;
import org.hongxi.jaws.rpc.DefaultProvider;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.stream.StreamSource;
import org.hongxi.jaws.wire.health.HealthCheckRequest;
import org.hongxi.jaws.wire.health.HealthCheckResponse;
import org.hongxi.jaws.wire.health.HealthCheckResponse.ServingStatus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Tests that a wire port with two services can tell them apart when they
 * declare the <b>same</b> method name.
 * <p>
 * The protobuf lookup prefers a match on the path's service name, but the
 * request handed to the pipeline kept carrying that proto-level name, so
 * provider resolution fell back to "first provider declaring this method" —
 * with two same-named methods that is a coin toss, and one of the two services
 * receives the other's implementation. The fix is to resolve the owning service
 * once, at the protobuf seam, and pass its Java interface name downstream.
 *
 * @author shenhongxi
 */
class WireMessageHandlerRoutingTest {

    /** Proto service names derived from each service's descriptor file. */
    private static final String ALPHA_PROTO = "grpc.health.v1.Health";
    private static final String BETA_PROTO = "grpc.reflection.v1.ServerReflection";

    /** Both services declare exactly one method called {@code call}. */
    public interface Alpha {
        HealthCheckResponse call(HealthCheckRequest request);
    }

    public interface Beta {
        ServerReflectionResponse call(ServerReflectionRequest request);
    }

    public static class AlphaImpl implements Alpha {
        @Override
        public HealthCheckResponse call(HealthCheckRequest request) {
            return HealthCheckResponse.newBuilder().setStatus(ServingStatus.SERVING).build();
        }
    }

    public static class BetaImpl implements Beta {
        @Override
        public ServerReflectionResponse call(ServerReflectionRequest request) {
            return ServerReflectionResponse.newBuilder()
                    .setValidHost("beta-answer").build();
        }
    }

    @Test
    void sameMethodNameAcrossServicesReachesTheRightProvider() throws Exception {
        WireMessageHandler handler = new WireMessageHandler();
        handler.addService(provider(Alpha.class, new AlphaImpl()),
                WireProtoTypes.fromServiceInterface(Alpha.class));
        handler.addService(provider(Beta.class, new BetaImpl()),
                WireProtoTypes.fromServiceInterface(Beta.class));

        // Addressed by its proto service name, the way a gRPC caller would.
        Object alphaReply = handler.handleAsync(
                request(ALPHA_PROTO, "Call",
                        HealthCheckRequest.newBuilder().setService("a").build().toByteArray()))
                .get(5, TimeUnit.SECONDS);
        // Both addressed by proto service name, the way a gRPC caller would:
        // neither name matches a provider serviceKey, so resolution can only be
        // right if the protobuf seam hands the owning interface downstream.
        Object betaReply = handler.handleAsync(
                request(BETA_PROTO, "Call",
                        ServerReflectionRequest.newBuilder().setListServices("").build()
                                .toByteArray()))
                .get(5, TimeUnit.SECONDS);

        // The pipeline hands back a Response wrapper; the wire dispatcher is
        // what unwraps it into a gRPC frame.
        Object alphaValue = value(alphaReply);
        Object betaValue = value(betaReply);

        assertInstanceOf(HealthCheckResponse.class, alphaValue,
                "Alpha must answer for Alpha" + describe(alphaReply));
        assertEquals(ServingStatus.SERVING, ((HealthCheckResponse) alphaValue).getStatus());
        assertInstanceOf(ServerReflectionResponse.class, betaValue,
                "Beta must answer for Beta" + describe(betaReply));
        assertEquals("beta-answer", ((ServerReflectionResponse) betaValue).getValidHost());
    }

    // ------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------

    private static Object value(Object reply) {
        return reply instanceof org.hongxi.jaws.rpc.Response response
                ? response.getValue() : reply;
    }

    /** Surface a pipeline error response instead of failing on its type alone. */
    private static String describe(Object reply) {
        if (reply instanceof org.hongxi.jaws.rpc.Response response
                && response.getThrowable() != null) {
            return " (error: " + response.getThrowable() + ")";
        }
        return " (type: " + (reply == null ? "null" : reply.getClass().getName()) + ")";
    }

    /** Providers are addressed by Java interface name, as {@code ServiceConfig} does. */
    private static <T> Provider<T> provider(Class<T> iface, T impl) {
        return new DefaultProvider<>(iface,
                new URL("wire", "127.0.0.1", 0, iface.getName()), impl);
    }

    private static Request request(String interfaceName, String method, byte[] payload) {
        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName(interfaceName);
        request.setMethodName(method);
        request.setRequestId(1L);
        request.setArguments(new Object[]{payload});
        return request;
    }
}
