package org.hongxi.jaws.sample.wire.provider;

import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.sample.wire.proto.GreeterService;
import org.hongxi.jaws.sample.wire.provider.service.GreeterServiceImpl;

/**
 * Wire (gRPC wire format) provider sample with Nacos registry.
 * <p>
 * Demonstrates the full Jaws framework pipeline with the wire protocol:
 * <ol>
 *   <li>Configure {@code WireProtocol} (protocol name = "wire")</li>
 *   <li>Configure Nacos registry for service discovery</li>
 *   <li>Export {@link GreeterService} via {@link ServiceConfig}</li>
 *   <li>The service is available to both Jaws wire consumers and grpcurl</li>
 * </ol>
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

        RegistryConfig registryConfig = new RegistryConfig();
        registryConfig.setProtocol("nacos");
        registryConfig.setId("defaultRegistry");
        registryConfig.setAddress("127.0.0.1");
        registryConfig.setPort(8848);

        ServiceConfig<GreeterService> serviceConfig = new ServiceConfig<>();
        serviceConfig.setInterface(GreeterService.class);
        serviceConfig.setRef(new GreeterServiceImpl());
        serviceConfig.setApplication("sample-wire-provider");
        serviceConfig.setModule("sample-wire");
        serviceConfig.setCheck(true);
        serviceConfig.setProtocol(protocolConfig);
        serviceConfig.setRegistry(registryConfig);
        serviceConfig.export();

        System.out.println("GreeterService exported via WireProtocol on port " + PORT);
        System.out.println("Registry: nacos://127.0.0.1:8848");
        System.out.println();
        System.out.println("Test with grpcurl:");
        System.out.println("  grpcurl -plaintext -import-path <proto-dir> -proto greeter.proto \\");
        System.out.println("    -d '{\"name\":\"World\"}' localhost:" + PORT + " greeter.Greeter/SayHello");
    }
}
