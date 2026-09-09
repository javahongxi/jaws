package org.hongxi.jaws.sample.wire.interop;

import org.hongxi.jaws.exception.JawsAbstractException;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.wire.WireClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/**
 * Proves that {@link WireClient} (jaws-wire, zero grpc-java dependency) can
 * call a standard grpc-java server over the gRPC wire format:
 * <ol>
 *   <li><b>Baseline + metadata</b>: unary SayHello with custom HTTP/2 headers
 *       read by the grpc-java {@code ServerInterceptor} in {@link GrpcServerMain}</li>
 *   <li><b>Compression</b>: gzip-compressed request frames decompressed natively
 *       by the grpc-java server</li>
 *   <li><b>Deadline &amp; cancellation</b>: grpc-timeout propagation; the client
 *       resets the stream with RST_STREAM(CANCEL) on expiry</li>
 *   <li><b>Bidirectional streaming</b>: WireClient opens a bidi stream to the
 *       grpc-java server's BidiGreet method, sends multiple request items,
 *       and receives responses concurrently</li>
 * </ol>
 * <p>
 * <b>Prerequisite:</b> start {@link GrpcServerMain} first.
 * <p>
 * Run:
 * <pre>
 *   # Terminal 1 — start the grpc-java server
 *   ./mvnw -q compile exec:java -pl jaws-samples/jaws-sample-wire-interop -am \
 *       -Dexec.mainClass="org.hongxi.jaws.sample.wire.interop.GrpcServerMain"
 *
 *   # Terminal 2 — run this demo
 *   ./mvnw -q compile exec:java -pl jaws-samples/jaws-sample-wire-interop -am \
 *       -Dexec.mainClass="org.hongxi.jaws.sample.wire.interop.WireCallGrpcDemo"
 * </pre>
 */
public class WireCallGrpcDemo {

    private static final int GRPC_PORT = 50060;

    public static void main(String[] args) throws Exception {
        // ---- 1. Baseline + metadata ----
        System.out.println("=== 1. Baseline + Metadata ===");
        WireClient wireClient = new WireClient(buildUrl(Map.of(
                "connectTimeout", "5000", "requestTimeout", "5000")));
        wireClient.open();

        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName("interop.Greeter");
        request.setMethodName("SayHello");
        request.setArguments(new Object[]{
                HelloRequest.newBuilder().setName("jaws-wire").build()
        });
        request.setAttachment("x-trace-id", "trace-abc-123");

        Response response = wireClient.request(request, HelloReply.parser());
        HelloReply reply = (HelloReply) response.getValue();
        System.out.println("Response: " + reply.getMessage());
        wireClient.close();

        // ---- 2. Gzip request compression ----
        System.out.println("\n=== 2. Gzip Request Compression ===");
        WireClient gzipClient = new WireClient(buildUrl(Map.of(
                "connectTimeout", "5000", "requestTimeout", "5000",
                "compression", "gzip")));
        gzipClient.open();

        DefaultRequest gzipRequest = new DefaultRequest();
        gzipRequest.setInterfaceName("interop.Greeter");
        gzipRequest.setMethodName("SayHello");
        gzipRequest.setArguments(new Object[]{
                HelloRequest.newBuilder().setName("gzip-user").build()
        });
        Response gzipResponse = gzipClient.request(gzipRequest, HelloReply.parser());
        System.out.println("Response: " + ((HelloReply) gzipResponse.getValue()).getMessage());
        gzipClient.close();

        // ---- 3. Deadline & cancellation ----
        System.out.println("\n=== 3. Deadline & Cancellation ===");
        WireClient slowClient = new WireClient(buildUrl(Map.of(
                "connectTimeout", "5000", "requestTimeout", "300")));
        slowClient.open();

        DefaultRequest slowRequest = new DefaultRequest();
        slowRequest.setInterfaceName("interop.Greeter");
        slowRequest.setMethodName("SayHello");
        slowRequest.setArguments(new Object[]{
                HelloRequest.newBuilder().setName("slow:jaws-wire").build()
        });
        try {
            slowClient.request(slowRequest, HelloReply.parser());
            System.out.println("ERROR: expected the call to time out");
        } catch (JawsAbstractException e) {
            System.out.println("WireClient failed as expected: " + e.getMessage());
            System.out.println("(deadline sent as grpc-timeout; stream reset with RST_STREAM CANCEL)");
        }
        slowClient.close();

        // ---- 4. Bidirectional streaming ----
        System.out.println("\n=== 4. Bidirectional Streaming ===");
        WireClient bidiClient = new WireClient(buildUrl(Map.of(
                "connectTimeout", "5000", "requestTimeout", "10000")));
        bidiClient.open();

        DefaultRequest bidiRequest = new DefaultRequest();
        bidiRequest.setInterfaceName("interop.Greeter");
        bidiRequest.setMethodName("BidiGreet");
        // requestBiStream does not need arguments in the request;
        // all items flow through the requestStream publisher
        bidiRequest.setArguments(new Object[0]);

        SubmissionPublisher<Object> requestPublisher = new SubmissionPublisher<>();
        CountDownLatch bidiLatch = new CountDownLatch(1);
        int[] bidiCount = {0};

        Flow.Publisher<Object> responsePublisher =
                bidiClient.requestBiStream(bidiRequest, requestPublisher, HelloReply.parser());
        responsePublisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Object item) {
                bidiCount[0]++;
                HelloReply reply = (HelloReply) item;
                System.out.println("  bidi response: " + reply.getMessage());
            }

            @Override
            public void onError(Throwable throwable) {
                System.err.println("  bidi stream error: " + throwable.getMessage());
                bidiLatch.countDown();
            }

            @Override
            public void onComplete() {
                System.out.println("  bidi stream completed (" + bidiCount[0] + " items)");
                bidiLatch.countDown();
            }
        });

        // Send request items with small delays
        Thread.sleep(200);
        requestPublisher.submit(HelloRequest.newBuilder().setName("Alice").build());
        Thread.sleep(100);
        requestPublisher.submit(HelloRequest.newBuilder().setName("Bob").build());
        Thread.sleep(100);
        requestPublisher.submit(HelloRequest.newBuilder().setName("Charlie").build());
        Thread.sleep(100);
        requestPublisher.close();

        if (!bidiLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            System.err.println("ERROR: bidi streaming call timed out");
        } else if (bidiCount[0] != 3) {
            System.err.println("ERROR: expected 3 bidi response items, got: " + bidiCount[0]);
        }
        bidiClient.close();

        System.out.println("\n=== WireClient -> grpc-java Passed ===");
    }

    private static URL buildUrl(Map<String, String> extraParams) {
        Map<String, String> params = new HashMap<>(extraParams);
        return new URL("grpc", "localhost", GRPC_PORT, "interop.Greeter", params);
    }
}
