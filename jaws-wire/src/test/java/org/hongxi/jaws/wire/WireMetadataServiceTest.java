package org.hongxi.jaws.wire;

import org.hongxi.jaws.common.util.ReflectUtils;
import org.hongxi.jaws.metadata.MetadataInfo;
import org.hongxi.jaws.metadata.MetadataService;
import org.hongxi.jaws.rpc.DefaultProvider;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.wire.health.HealthCheckRequest;
import org.hongxi.jaws.wire.health.HealthCheckResponse;
import org.hongxi.jaws.wire.health.HealthCheckResponse.ServingStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the internal {@link MetadataService} over the wire protocol,
 * the metadata pull channel of application-level service discovery.
 * <p>
 * The interface is plain Java ({@code String revision -> MetadataInfo}) with
 * no protobuf types, so both legs must pass through the StringValue envelope
 * ({@link WireMetadataCodec}) while the provider pipeline keeps carrying the
 * plain Java types. The request is built exactly like
 * {@code AppLevelRegistryDirectory.fetchMetadata} does.
 *
 * @author shenhongxi
 */
class WireMetadataServiceTest {

    /** A business wire service sharing the port with the metadata channel. */
    public interface Probe {
        HealthCheckResponse ping(HealthCheckRequest request);
    }

    public static class ProbeImpl implements Probe {
        @Override
        public HealthCheckResponse ping(HealthCheckRequest request) {
            return HealthCheckResponse.newBuilder().setStatus(ServingStatus.SERVING).build();
        }
    }

    private final WireProtocol protocol = new WireProtocol();
    private final List<URL> exported = new ArrayList<>();
    private final List<Reference<?>> references = new ArrayList<>();
    private final Map<String, MetadataInfo> store = new HashMap<>();

    @AfterEach
    void tearDown() {
        for (Reference<?> reference : references) {
            reference.destroy();
        }
        references.clear();
        for (URL url : exported) {
            protocol.unexport(url);
        }
        exported.clear();
    }

    @Test
    void metadataPullRoundTripPreservesRevision() throws Exception {
        int port = freePort();
        MetadataInfo info = new MetadataInfo("demo-app");
        info.addServiceUrl(new URL("wire", "127.0.0.1", port, "demo.DemoService"));
        info.addServiceUrl(new URL("wire", "127.0.0.1", port, "demo.OrderService"));
        info.init();
        store.put(info.getRevision(), info);
        exportMetadataService(port);

        Reference<MetadataService> reference = referMetadataService(port);
        Object value = reference.call(metadataRequest(info.getRevision())).getValue();

        assertTrue(value instanceof MetadataInfo, "the plain MetadataInfo must survive the wire");
        MetadataInfo restored = (MetadataInfo) value;
        assertEquals(info.getApplication(), restored.getApplication());
        assertEquals(info.getRevision(), restored.getRevision());
        assertEquals(info.getServices(), restored.getServices());
        assertEquals(info.getRevision(), restored.calRevision(),
                "content hash must survive the JSON envelope unchanged");
    }

    @Test
    void unknownRevisionReturnsNull() throws Exception {
        int port = freePort();
        exportMetadataService(port);

        Reference<MetadataService> reference = referMetadataService(port);
        Object value = reference.call(metadataRequest("no-such-revision")).getValue();

        assertNull(value, "an unknown revision must decode to null, not an empty object");
    }

    @Test
    void businessServiceOnTheSamePortStillWorks() throws Exception {
        int port = freePort();
        exportMetadataService(port);
        URL probeUrl = new URL("wire", "127.0.0.1", port, Probe.class.getName());
        protocol.export(new DefaultProvider<>(Probe.class, probeUrl, new ProbeImpl()));
        exported.add(probeUrl);

        // The metadata channel answers on the shared port...
        MetadataInfo info = new MetadataInfo("demo-app");
        info.addServiceUrl(new URL("wire", "127.0.0.1", port, "demo.DemoService"));
        info.init();
        store.put(info.getRevision(), info);
        Reference<MetadataService> reference = referMetadataService(port);
        assertTrue(reference.call(metadataRequest(info.getRevision())).getValue() instanceof MetadataInfo);

        // ...and the protobuf business service keeps its own typed path.
        Reference<Probe> probe = protocol.refer(Probe.class, probeUrl);
        references.add(probe);
        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName(Probe.class.getName());
        request.setMethodName("ping");
        request.setArguments(new Object[]{HealthCheckRequest.newBuilder().setService("probe").build()});
        assertEquals(ServingStatus.SERVING,
                ((HealthCheckResponse) probe.call(request).getValue()).getStatus());
    }

    @Test
    void protoTypesRegisterBothMethodNames() {
        WireProtoTypes protoTypes = WireProtoTypes.fromServiceInterface(MetadataService.class);
        assertTrue(protoTypes.hasMethod("getMetadataInfo"), "Java camelCase name must resolve");
        assertTrue(protoTypes.hasMethod("GetMetadataInfo"), "gRPC PascalCase name must resolve");
    }

    // ------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------

    private void exportMetadataService(int port) {
        URL url = new URL("wire", "127.0.0.1", port, MetadataService.class.getName());
        MetadataService impl = store::get;
        protocol.export(new DefaultProvider<>(MetadataService.class, url, impl));
        exported.add(url);
    }

    private Reference<MetadataService> referMetadataService(int port) {
        URL url = new URL("wire", "127.0.0.1", port, MetadataService.class.getName());
        Reference<MetadataService> reference = protocol.refer(MetadataService.class, url);
        references.add(reference);
        return reference;
    }

    /** Built exactly like {@code AppLevelRegistryDirectory.fetchMetadata}. */
    private static DefaultRequest metadataRequest(String revision) throws NoSuchMethodException {
        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName(MetadataService.class.getName());
        request.setMethodName("getMetadataInfo");
        request.setParamDesc(ReflectUtils.getMethodParamDesc(
                MetadataService.class.getMethod("getMetadataInfo", String.class)));
        request.setArguments(new Object[]{revision});
        return request;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}