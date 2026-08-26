package org.hongxi.jaws.sample.wire.provider;

import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.sample.wire.proto.GreeterService;
import org.hongxi.jaws.sample.wire.provider.service.GreeterServiceImpl;

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
 * The consumer connects directly via {@code directUrl} without registry discovery.
 * <p>
 * Test with grpcurl:
 * <pre>
 *   grpcurl -plaintext -proto greeter.proto -d '{"name":"World"}' \
 *     localhost:50051 greeter.Greeter/SayHello
 * </pre>
 */
public class WireProvider {

    private static final int PORT = Integer.parseInt(System.getProperty("port", "50051"));

    public static void main(String[] args) {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName("wire");
        protocolConfig.setId("wire");
        protocolConfig.setTransportFactory("wire");
        protocolConfig.setPort(PORT);

        ServiceConfig<GreeterService> serviceConfig = new ServiceConfig<>();
        serviceConfig.setInterface(GreeterService.class);
        serviceConfig.setRef(new GreeterServiceImpl());
        serviceConfig.setApplication("sample-wire-provider");
        serviceConfig.setModule("sample-wire");
        serviceConfig.setCheck(true);
        serviceConfig.setProtocol(protocolConfig);
        serviceConfig.export();
        System.out.println("GreeterService exported via WireProtocol (direct mode, no registry).");
        System.out.println("Provider listening on port " + PORT + ". Consumer should use directUrl=127.0.0.1:" + PORT);
        System.out.println();
        System.out.println("Test with grpcurl:");
        System.out.println("  grpcurl -plaintext -import-path <proto-dir> -proto greeter.proto \\");
        System.out.println("    -d '{\"name\":\"World\"}' localhost:" + PORT + " greeter.Greeter/SayHello");
    }
}
