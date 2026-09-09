package org.hongxi.jaws.sample.wire.provider;

import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.sample.wire.proto.GreeterService;
import org.hongxi.jaws.sample.wire.provider.service.GreeterServiceImpl;

import java.util.concurrent.CountDownLatch;

/**
 * Wire (gRPC wire format) provider sample in direct mode.
 * <p>
 * Demonstrates the Jaws framework pipeline with the wire protocol:
 * <ol>
 *   <li>Configure {@code WireProtocol} (protocol name = "wire")</li>
 *   <li>No registry (export only, skip registration)</li>
 *   <li>Export {@link GreeterService} via {@link ServiceConfig}</li>
 *   <li>The service is available to both Jaws wire consumers and grpcurl</li>
 * </ol>
 * <p>
 * Responses are gzip-compressed on the wire ({@code compression=gzip});
 * callers that do not advertise gzip in grpc-accept-encoding get identity
 * responses automatically. Request attachments (gRPC metadata) are surfaced
 * to the service via {@code RpcContext}.
 * <p>
 * The consumer connects directly via {@code directUrl} without registry discovery.
 * <p>
 * Wire-specific transport parameters demonstrated:
 * <ul>
 *   <li>{@code maxConnectionIdleMs} — close idle connections after 5 minutes</li>
 *   <li>{@code maxConnectionAgeMs} — recycle connections after 30 minutes</li>
 *   <li>{@code maxInboundMetadataSize} — reject request metadata larger than 16KB</li>
 *   <li>{@code permitPingIntervalMs} — guard against overly frequent client PINGs</li>
 * </ul>
 * <p>
 * Test with grpcurl (no proto file needed, via server reflection):
 * <pre>
 *   grpcurl -plaintext -d '{"name":"World"}' \
 *     localhost:50051 greeter.Greeter/SayHello
 * </pre>
 */
public class WireProvider {

    private static final int PORT = Integer.parseInt(System.getProperty("port", "50051"));

    public static void main(String[] args) throws Exception {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName("wire");
        protocolConfig.setId("wire");
        protocolConfig.setTransportFactory("wire");
        protocolConfig.setPort(PORT);
        // Compress response messages with gzip for callers that accept it
        protocolConfig.setCompression("gzip");
        // Connection lifecycle: close idle connections after 5 minutes
        protocolConfig.setParameter("maxConnectionIdleMs", "300000");
        // Recycle connections after 30 minutes (max connection age)
        protocolConfig.setParameter("maxConnectionAgeMs", "1800000");
        // Grace period after GOAWAY for in-flight streams to complete
        protocolConfig.setParameter("maxConnectionAgeGraceMs", "5000");
        // Reject request metadata larger than 16KB
        protocolConfig.setParameter("maxInboundMetadataSize", "16384");
        // Guard against overly frequent client PINGs (default 5min)
        protocolConfig.setParameter("permitPingIntervalMs", "300000");

        ServiceConfig<GreeterService> serviceConfig = new ServiceConfig<>();
        serviceConfig.setInterface(GreeterService.class);
        serviceConfig.setRef(new GreeterServiceImpl());
        serviceConfig.setApplication("sample-wire-provider");
        serviceConfig.setModule("sample-wire");
        serviceConfig.setCheck(true);
        serviceConfig.setProtocol(protocolConfig);
        serviceConfig.export();
        System.out.println("GreeterService exported via WireProtocol (direct mode, no registry).");
        System.out.println("Responses compressed with gzip for callers advertising grpc-accept-encoding.");
        System.out.println("Connection lifecycle: maxIdle=5min, maxAge=30min, maxInboundMetadata=16KB.");
        System.out.println("Provider listening on port " + PORT + ". Consumer should use directUrl=127.0.0.1:" + PORT);
        System.out.println();
        System.out.println("Test with grpcurl (server reflection enabled, no proto file needed):");
        System.out.println("  grpcurl -plaintext -d '{\"name\":\"World\"}' \\");
        System.out.println("    localhost:" + PORT + " greeter.Greeter/SayHello");

        // Block main thread to prevent JVM exit (Netty event loop threads may be daemon)
        new CountDownLatch(1).await();
    }
}
