package org.hongxi.jaws.sample.wire.boot.provider;

import org.hongxi.jaws.spring.boot.annotation.EnableJaws;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.concurrent.CountDownLatch;

/**
 * Wire (gRPC wire format) provider in Spring Boot mode, direct-connect demo.
 * <p>
 * The only difference from the plain {@code jaws-sample-wire-provider} is how
 * services are wired: {@code @JawsService} beans are exported by the starter
 * on {@code ContextRefreshedEvent}, and the protocol comes from
 * {@code application.yml} ({@code jaws.protocol.name=wire},
 * {@code transport-factory=wire}) instead of a hand-built {@code ProtocolConfig}.
 * <p>
 * No external registry is involved (the JVM-scoped {@code local} registry keeps
 * registrations in-process), so consumers connect via {@code directUrl}. The
 * port still serves both exported services on one socket, and grpcurl works
 * against it as usual:
 * <pre>
 *   grpcurl -plaintext localhost:50051 list
 *   grpcurl -plaintext -d '{"name":"World"}' localhost:50051 greeter.Greeter/SayHello
 * </pre>
 */
@EnableJaws
@SpringBootApplication
public class WireBootProvider {

    public static void main(String[] args) throws InterruptedException {
        SpringApplication.run(WireBootProvider.class, args);
        // The wire server runs on daemon threads, so hold the main thread; a
        // Ctrl-C still goes through Spring's shutdown hook (graceful unexport)
        // and then releases this latch.
        new CountDownLatch(1).await();
    }
}
