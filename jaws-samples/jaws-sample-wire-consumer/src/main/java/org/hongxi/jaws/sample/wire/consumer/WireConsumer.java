package org.hongxi.jaws.sample.wire.consumer;

import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.sample.wire.proto.GreeterService;
import org.hongxi.jaws.sample.wire.proto.HelloReply;
import org.hongxi.jaws.sample.wire.proto.HelloRequest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;

/**
 * Wire (gRPC wire format) consumer sample in direct mode.
 * <p>
 * Demonstrates the Jaws framework pipeline on the consumer side:
 * <ol>
 *   <li>Configure {@code WireProtocol} (protocol name = "wire")</li>
 *   <li>Use {@code directUrl} (bypasses registry discovery)</li>
 *   <li>Obtain a proxy to {@link GreeterService} via {@link ReferenceConfig}</li>
 *   <li>Invoke the service with protobuf {@link HelloRequest} arguments</li>
 * </ol>
 * <p>
 * The pipeline is exercised: cluster → load balance → filter chain
 * → WireReference → WireClient → gRPC wire format.
 * <p>
 * Also demonstrates the newer gRPC semantics:
 * <ul>
 *   <li>Metadata: {@link RpcContext} attachments travel as custom HTTP/2
 *       headers and reach the provider as request attachments</li>
 *   <li>Compression: requests are gzip-compressed on the wire
 *       ({@code compression=gzip})</li>
 * </ul>
 * <p>
 * Run {@code WireProvider} first before starting this consumer.
 */
public class WireConsumer {

    private static final String DIRECT_URL = System.getProperty("directUrl", "127.0.0.1:50051");

    public static void main(String[] args) throws Exception {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName("wire");
        protocolConfig.setId("wire");
        protocolConfig.setTransportFactory("wire");
        // Compress request messages with gzip (grpc-encoding: gzip)
        protocolConfig.setCompression("gzip");

        ReferenceConfig<GreeterService> ref = new ReferenceConfig<>();
        ref.setInterface(GreeterService.class);
        ref.setApplication("sample-wire-consumer");
        ref.setModule("sample-wire");
        ref.setCheck(false);
        ref.setRequestTimeout(5000);
        ref.setProtocol(protocolConfig);
        ref.setDirectUrl(DIRECT_URL);

        GreeterService greeterService = ref.getRef();

        // Metadata: RpcContext attachments are sent as gRPC custom headers
        // (x-trace-id) and surfaced to the provider as request attachments
        RpcContext.getContext().setRpcAttachment("x-trace-id", "trace-demo-001");

        // First call
        HelloReply reply1 = greeterService.sayHello(
                HelloRequest.newBuilder().setName("World").build());
        System.out.println("Response: " + reply1.getMessage());

        // Second call
        HelloReply reply2 = greeterService.sayHello(
                HelloRequest.newBuilder().setName("jaws-wire").build());
        System.out.println("Response: " + reply2.getMessage());

        // Server streaming call
        System.out.println("\n--- Server Streaming ---");
        CountDownLatch latch = new CountDownLatch(1);
        greeterService.sayHelloStream(
                HelloRequest.newBuilder().setName("StreamUser").build()
        ).subscribe(new Flow.Subscriber<HelloReply>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HelloReply item) {
                System.out.println("Stream item: " + item.getMessage());
            }

            @Override
            public void onError(Throwable throwable) {
                System.err.println("Stream error: " + throwable.getMessage());
                latch.countDown();
            }

            @Override
            public void onComplete() {
                System.out.println("Stream completed.");
                latch.countDown();
            }
        });
        latch.await();

        System.out.println("Done.");

        // Force exit (Netty/Curator non-daemon threads prevent JVM exit)
        System.exit(0);
    }
}
