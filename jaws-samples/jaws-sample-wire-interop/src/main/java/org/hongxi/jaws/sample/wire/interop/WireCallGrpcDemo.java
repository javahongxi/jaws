package org.hongxi.jaws.sample.wire.interop;

import org.hongxi.jaws.exception.JawsAbstractException;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.http2.Http2Constants;
import org.hongxi.jaws.transport.http2.StreamType;
import org.hongxi.jaws.wire.WireClient;

import org.hongxi.jaws.transport.StreamSubject;
import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

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
 *   <li><b>Server-streaming</b>: WireClient opens a server-stream to the
 *       grpc-java server's SayHelloStream method and receives multiple
 *       greeting replies</li>
 *   <li><b>Client-streaming</b>: WireClient opens a client-stream to the
 *       grpc-java server's ClientStreamGreet method, sends multiple names,
 *       and receives a single aggregated reply</li>
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

    /** Failed checks; a non-zero count must fail the process so run-sample.sh counts it. */
    private static int failures = 0;

    private static void fail(String message) {
        System.err.println("ERROR: " + message);
        failures++;
    }

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
        boolean deadlineFired = false;
        try {
            // WireClient.request() does not block since the all-async refactor: the
            // wait and the timeout surfacing both happen in getValue(), so the
            // assertion has to consume the returned future.
            slowClient.request(slowRequest, HelloReply.parser()).getValue();
        } catch (JawsAbstractException e) {
            deadlineFired = true;
            System.out.println("WireClient failed as expected: " + e.getMessage());
            System.out.println("(deadline sent as grpc-timeout; stream reset with RST_STREAM CANCEL)");
        }
        if (!deadlineFired) {
            fail("expected the call to time out");
        }
        slowClient.close();

        // ---- 4. Server-streaming ----
        System.out.println("\n=== 4. Server Streaming ===");
        WireClient serverStreamClient = new WireClient(buildUrl(Map.of(
                "connectTimeout", "5000", "requestTimeout", "10000")));
        serverStreamClient.open();

        DefaultRequest serverStreamRequest = new DefaultRequest();
        serverStreamRequest.setInterfaceName("interop.Greeter");
        serverStreamRequest.setMethodName("SayHelloStream");
        serverStreamRequest.setArguments(new Object[]{
                HelloRequest.newBuilder().setName("jaws-wire-stream").build()
        });

        CountDownLatch serverStreamLatch = new CountDownLatch(1);
        int[] serverStreamCount = {0};

        StreamSource<Object> serverStreamResponse =
                serverStreamClient.requestStream(serverStreamRequest, HelloReply.parser());
        serverStreamResponse.subscribe(new StreamObserver<>() {
            @Override
            public void onNext(Object item) {
                serverStreamCount[0]++;
                HelloReply reply = (HelloReply) item;
                System.out.println("  server-stream item: " + reply.getMessage());
            }

            @Override
            public void onError(Throwable throwable) {
                System.err.println("  server-stream error: " + throwable.getMessage());
                serverStreamLatch.countDown();
            }

            @Override
            public void onCompleted() {
                System.out.println("  server-stream completed (" + serverStreamCount[0] + " items)");
                serverStreamLatch.countDown();
            }
        });

        if (!serverStreamLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            fail("server-streaming call timed out");
        } else if (serverStreamCount[0] != 3) {
            fail("expected 3 server-stream items, got: " + serverStreamCount[0]);
        }
        serverStreamClient.close();

        // ---- 5. Client-streaming ----
        System.out.println("\n=== 5. Client Streaming ===");
        WireClient clientStreamClient = new WireClient(buildUrl(Map.of(
                "connectTimeout", "5000", "requestTimeout", "10000")));
        clientStreamClient.open();

        DefaultRequest clientStreamRequest = new DefaultRequest();
        clientStreamRequest.setInterfaceName("interop.Greeter");
        clientStreamRequest.setMethodName("ClientStreamGreet");
        clientStreamRequest.setArguments(new Object[0]);
        clientStreamRequest.setAttachment(Http2Constants.HEADER_STREAMING, StreamType.CLIENT.getValue());

        StreamSubject<Object> clientStreamObserver = new StreamSubject<>();
        CountDownLatch clientStreamLatch = new CountDownLatch(1);

        StreamSource<Object> clientStreamResponse =
                clientStreamClient.requestStream(clientStreamRequest, clientStreamObserver, HelloReply.parser());
        clientStreamResponse.subscribe(new StreamObserver<>() {
            @Override
            public void onNext(Object item) {
                HelloReply reply = (HelloReply) item;
                System.out.println("  client-stream response: " + reply.getMessage());
            }

            @Override
            public void onError(Throwable throwable) {
                System.err.println("  client-stream error: " + throwable.getMessage());
                clientStreamLatch.countDown();
            }

            @Override
            public void onCompleted() {
                System.out.println("  client-stream completed");
                clientStreamLatch.countDown();
            }
        });

        Thread.sleep(200);
        clientStreamObserver.onNext(HelloRequest.newBuilder().setName("Alice").build());
        Thread.sleep(100);
        clientStreamObserver.onNext(HelloRequest.newBuilder().setName("Bob").build());
        Thread.sleep(100);
        clientStreamObserver.onNext(HelloRequest.newBuilder().setName("Charlie").build());
        Thread.sleep(100);
        clientStreamObserver.onCompleted();

        if (!clientStreamLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            fail("client-streaming call timed out");
        }
        clientStreamClient.close();

        // ---- 6. Bidirectional streaming ----
        System.out.println("\n=== 6. Bidirectional Streaming ===");
        WireClient bidiClient = new WireClient(buildUrl(Map.of(
                "connectTimeout", "5000", "requestTimeout", "10000")));
        bidiClient.open();

        DefaultRequest bidiRequest = new DefaultRequest();
        bidiRequest.setInterfaceName("interop.Greeter");
        bidiRequest.setMethodName("BidiGreet");
        // requestBiStream does not need arguments in the request;
        // all items flow through the requestStream publisher
        bidiRequest.setArguments(new Object[0]);

        StreamSubject<Object> requestObserver = new StreamSubject<>();
        CountDownLatch bidiLatch = new CountDownLatch(1);
        int[] bidiCount = {0};

        StreamSource<Object> responseSource =
                bidiClient.requestBidiStream(bidiRequest, requestObserver, HelloReply.parser());
        responseSource.subscribe(new StreamObserver<>() {
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
            public void onCompleted() {
                System.out.println("  bidi stream completed (" + bidiCount[0] + " items)");
                bidiLatch.countDown();
            }
        });

        // Send request items with small delays
        Thread.sleep(200);
        requestObserver.onNext(HelloRequest.newBuilder().setName("Alice").build());
        Thread.sleep(100);
        requestObserver.onNext(HelloRequest.newBuilder().setName("Bob").build());
        Thread.sleep(100);
        requestObserver.onNext(HelloRequest.newBuilder().setName("Charlie").build());
        Thread.sleep(100);
        requestObserver.onCompleted();

        if (!bidiLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            fail("bidi streaming call timed out");
        } else if (bidiCount[0] != 3) {
            fail("expected 3 bidi response items, got: " + bidiCount[0]);
        }
        bidiClient.close();

        if (failures == 0) {
            System.out.println("\n=== WireClient -> grpc-java Passed ===");
        } else {
            System.err.println("\n=== WireClient -> grpc-java FAILED: " + failures + " check(s) ===");
        }

        // Force exit (Netty non-daemon threads prevent JVM exit), carrying the
        // verdict in the exit code so the runner counts this demo honestly.
        System.exit(failures == 0 ? 0 : 1);
    }

    private static URL buildUrl(Map<String, String> extraParams) {
        Map<String, String> params = new HashMap<>(extraParams);
        return new URL("grpc", "localhost", GRPC_PORT, "interop.Greeter", params);
    }
}
