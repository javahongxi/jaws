package org.hongxi.jaws.sample.wire.consumer;

import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.sample.wire.proto.GreeterService;
import org.hongxi.jaws.sample.wire.proto.HelloReply;
import org.hongxi.jaws.sample.wire.proto.HelloRequest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

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
 *   <li>Keepalive: PING every 30s to detect dead peers
 *       ({@code keepaliveTimeMs=30000})</li>
 *   <li>Retry: up to 3 attempts with exponential backoff on UNAVAILABLE
 *       ({@code retryMaxAttempts=3})</li>
 *   <li>Max inbound metadata: reject response metadata larger than 16KB
 *       ({@code maxInboundMetadataSize=16384})</li>
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
        // Keepalive: send PING every 30s, timeout after 20s
        protocolConfig.setParameter("keepaliveTimeMs", "30000");
        protocolConfig.setParameter("keepaliveTimeoutMs", "20000");
        // Retry: up to 3 attempts with exponential backoff (100ms initial, 1s max)
        protocolConfig.setParameter("retryMaxAttempts", "3");
        protocolConfig.setParameter("retryInitialBackoffMs", "100");
        protocolConfig.setParameter("retryMaxBackoffMs", "1000");
        protocolConfig.setParameter("retryBackoffMultiplierPct", "200");
        protocolConfig.setParameter("retryJitterPct", "20");
        // Reject response metadata larger than 16KB
        protocolConfig.setParameter("maxInboundMetadataSize", "16384");

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
        ).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
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

        // Client streaming call: stream names, get a single aggregated reply
        System.out.println("\n--- Client Streaming ---");
        SubmissionPublisher<HelloRequest> clientStreamPublisher = new SubmissionPublisher<>();

        // Start sending items in a background thread while the main thread
        // blocks on the client-streaming call (which returns a single HelloReply)
        Thread senderThread = new Thread(() -> {
            try {
                // Wait for subscription to propagate
                Thread.sleep(200);
                clientStreamPublisher.submit(HelloRequest.newBuilder().setName("Alice").build());
                Thread.sleep(100);
                clientStreamPublisher.submit(HelloRequest.newBuilder().setName("Bob").build());
                Thread.sleep(100);
                clientStreamPublisher.submit(HelloRequest.newBuilder().setName("Charlie").build());
                Thread.sleep(100);
                clientStreamPublisher.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        senderThread.setDaemon(true);
        senderThread.start();

        // This call blocks until all request items are sent and the server replies
        HelloReply clientStreamReply = greeterService.clientStreamGreet(clientStreamPublisher);
        System.out.println("Client stream response: " + clientStreamReply.getMessage());
        senderThread.join(5000);

        // Bidirectional streaming call
        System.out.println("\n--- Bidirectional Streaming ---");
        CountDownLatch bidiLatch = new CountDownLatch(1);
        SubmissionPublisher<HelloRequest> requestPublisher = new SubmissionPublisher<>();

        Flow.Publisher<HelloReply> bidiResponse = greeterService.bidiGreet(requestPublisher);
        bidiResponse.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HelloReply item) {
                System.out.println("Bidi response: " + item.getMessage());
            }

            @Override
            public void onError(Throwable throwable) {
                System.err.println("Bidi stream error: " + throwable.getMessage());
                throwable.printStackTrace();
                bidiLatch.countDown();
            }

            @Override
            public void onComplete() {
                System.out.println("Bidi stream completed.");
                bidiLatch.countDown();
            }
        });

        // Wait a bit for subscription to be established, then send request items
        Thread.sleep(200);
        requestPublisher.submit(HelloRequest.newBuilder().setName("Alice").build());
        Thread.sleep(100);
        requestPublisher.submit(HelloRequest.newBuilder().setName("Bob").build());
        Thread.sleep(100);
        requestPublisher.submit(HelloRequest.newBuilder().setName("Charlie").build());
        Thread.sleep(100);
        requestPublisher.close();

        if (!bidiLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            System.err.println("Bidi streaming timed out after 10 seconds!");
        }

        System.out.println("Done.");

        // Force exit (Netty/Curator non-daemon threads prevent JVM exit)
        System.exit(0);
    }
}
