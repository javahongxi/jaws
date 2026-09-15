package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import org.hongxi.jaws.wire.reflection.ServerReflectionRequest;
import org.hongxi.jaws.wire.reflection.ServerReflectionResponse;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link WireReflectionService} ListServices enumeration.
 * <p>
 * Standard gRPC reflection (grpc-java's {@code ProtoReflectionService}) lists
 * ALL registered services, including the {@code grpc.*} built-ins, so that
 * tools like {@code grpcurl} see the same set a reference server exposes.
 * This is a regression guard for a past deviation in jaws that filtered out
 * any service name starting with {@code "grpc."}, which made {@code grpcurl
 * list} under-report (and, on a server with only built-ins, return "No
 * services").
 *
 * @author shenhongxi
 */
class WireReflectionServiceTest {

    private static Set<String> listedServices(WireHandlerRegistry registry) {
        WireReflectionService svc = new WireReflectionService(
                registry::listServiceNames, registry::collectFileDescriptors);
        ServerReflectionResponse resp = svc.handleRequest(
                ServerReflectionRequest.newBuilder().setListServices("").build());
        return resp.getListServicesResponse().getServiceList().stream()
                .map(s -> s.getName())
                .collect(Collectors.toSet());
    }

    /** Minimal unary handler stub; only used to register a business service path. */
    private static final WireMethodHandler NOOP = new WireMethodHandler() {
        @Override
        public Message handle(Message request) {
            return request;
        }

        @Override
        public Parser<? extends Message> getRequestParser() {
            return null;
        }
    };

    @Test
    void listServicesIncludesBuiltInHealthService() {
        WireHandlerRegistry registry = new WireHandlerRegistry();
        new WireHealthService().registerTo(registry);

        Set<String> listed = listedServices(registry);
        assertTrue(listed.contains("grpc.health.v1.Health"),
                "ListServices must include grpc.* built-ins per standard gRPC reflection, got: " + listed);
    }

    @Test
    void listServicesIncludesBothBusinessAndBuiltInServices() {
        WireHandlerRegistry registry = new WireHandlerRegistry();
        new WireHealthService().registerTo(registry);
        registry.register("demo.DemoService", "Ping", NOOP);

        Set<String> listed = listedServices(registry);
        assertTrue(listed.contains("demo.DemoService"),
                "business service should be listed, got: " + listed);
        assertTrue(listed.contains("grpc.health.v1.Health"),
                "built-in health service should also be listed (no grpc.* filtering), got: " + listed);
    }
}
