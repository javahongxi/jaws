package org.hongxi.jaws.sample.wire.boot.provider;

import org.hongxi.jaws.spring.boot.annotation.EnableJaws;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Wire (gRPC wire format) provider in Spring Boot mode, published through Nacos.
 * <p>
 * The only difference from the plain {@code jaws-sample-wire-provider} is how
 * services are wired: {@code @JawsService} beans are exported by the starter
 * on {@code ContextRefreshedEvent}, and the protocol comes from
 * {@code application.yml} ({@code jaws.protocol.name=wire}; the transport follows
 * the protocol name, so {@code transport-factory} stays unset) instead of a
 * hand-built {@code ProtocolConfig}.
 * <p>
 * Unlike that plain provider, which exports on a fixed address, this one registers
 * itself: {@code jaws.registry.address=nacos://127.0.0.1:8848} publishes both
 * services as {@code jaws/<interface FQN>} in group {@code default_rpc}, so a
 * consumer can resolve them without being told a port — that is the case
 * sweet's {@code example/jaws-wire -registry=nacos} drives. Start Nacos first.
 * <p>
 * The port still serves both exported services on one socket, and grpcurl works
 * against it as usual:
 * <pre>
 *   grpcurl -plaintext localhost:50051 list
 *   grpcurl -plaintext -d '{"name":"World"}' localhost:50051 greeter.Greeter/SayHello
 * </pre>
 */
@EnableJaws
@SpringBootApplication
public class WireBootProvider {

    public static void main(String[] args) {
        SpringApplication.run(WireBootProvider.class, args);
    }
}
