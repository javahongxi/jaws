package org.hongxi.jaws.sample.benchmark;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.shaded.io.grpc.netty.NegotiationType;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.common.threadpool.DefaultThreadFactory;
import org.hongxi.jaws.common.threadpool.EagerThreadPoolExecutor;
import org.hongxi.jaws.sample.wire.proto.GreeterService;
import org.hongxi.jaws.sample.wire.proto.HelloReply;
import org.hongxi.jaws.sample.wire.proto.HelloRequest;
import org.hongxi.jaws.transport.AbortPolicyWithStats;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * gRPC (grpc-java stack) performance benchmark, interoperable with the jaws
 * wire server.
 *
 * <p>The load generator is always a grpc-java client, in two modes:
 * <ul>
 *   <li>{@code -Dserver=wire} (default): the server side is the jaws wire
 *       provider — proves protocol interoperability and measures the same
 *       service behind grpc-java's stack (third-party reverse verification).</li>
 *   <li>{@code -Dserver=grpc}: the server side is a pure grpc-java netty
 *       server — the reference number of the real gRPC. Its business executor
 *       mirrors the wire server's pool (EagerThreadPool min20/max200/queue0),
 *       i.e. the production-form thread model rather than the unbounded
 *       default cached pool.</li>
 * </ul>
 *
 * <p>Both sides speak the exact path the jaws wire leg registers:
 * {@code /org.hongxi.jaws.sample.wire.proto.GreeterService/SayHello}. The
 * message classes are the generated {@code HelloRequest}/{@code HelloReply};
 * the service is declared with a hand-built {@link MethodDescriptor} so no
 * gRPC codegen and no proto change are needed.
 *
 * <pre>
 * System properties (passed via -D):
 *   server      - Server stack: wire (default) / grpc
 *   role        - Run role: all (default, same process) / provider / consumer
 *   threads     - Concurrency thread count, default 4 (only for all / consumer)
 *   warmup      - Warm-up seconds, default 5
 *   duration    - Measurement seconds, default 10
 *   port        - Server port, default 50051
 *   host        - Provider address, consumer direct-connect target, default 127.0.0.1
 *
 * Examples:
 *   # Interop: jaws wire provider (started via bench-wire ROLE=provider) + grpc-java client
 *   java -Dserver=wire -Drole=consumer -Dthreads=20 -Dduration=40 ...
 *   # Reference: pure grpc-java server + client, same process
 *   java -Dserver=grpc -Drole=all -Dthreads=20 -Dduration=40 ...
 * </pre>
 */
public class GrpcBenchmark {

    private static final String SERVER = System.getProperty("server", "wire");
    private static final String ROLE = System.getProperty("role", "all");
    private static final int THREADS = Integer.parseInt(System.getProperty("threads", "4"));
    private static final int WARMUP_SECONDS = Integer.parseInt(System.getProperty("warmup", "5"));
    private static final int DURATION_SECONDS = Integer.parseInt(System.getProperty("duration", "10"));
    private static final int PORT = Integer.parseInt(System.getProperty("port", "50051"));
    private static final String HOST = System.getProperty("host", "127.0.0.1");

    private static final String BENCHMARK_NAME = "benchmark";
    private static final HelloRequest REQUEST =
            HelloRequest.newBuilder().setName(BENCHMARK_NAME).build();

    /**
     * The service name must match what the jaws wire leg registers (the Java
     * interface name), and the method must be the gRPC-style {@code SayHello} —
     * the wire client converts {@code sayHello} with the same rule
     * (WireProtoTypes.toGrpcMethodName).
     */
    private static final String SERVICE_NAME =
            "org.hongxi.jaws.sample.wire.proto.GreeterService";

    private static final MethodDescriptor<HelloRequest, HelloReply> SAY_HELLO =
            MethodDescriptor.<HelloRequest, HelloReply>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(MethodDescriptor.generateFullMethodName(
                            SERVICE_NAME, "SayHello"))
                    .setRequestMarshaller(ProtoUtils.marshaller(HelloRequest.getDefaultInstance()))
                    .setResponseMarshaller(ProtoUtils.marshaller(HelloReply.getDefaultInstance()))
                    .build();

    private static final CallOptions CALL_OPTIONS =
            System.getProperty("deadline", "off").equals("on")
                    ? CallOptions.DEFAULT.withDeadlineAfter(30, TimeUnit.SECONDS)
                    : CallOptions.DEFAULT;

    /** Error counters keyed by error type, across warmup and measurement. */
    private static final ConcurrentMap<String, LongAdder> ERROR_COUNTERS = new ConcurrentHashMap<>();

    private static volatile ManagedChannel channel;

    public static void main(String[] args) throws Exception {
        checkConfig();

        System.out.println("============================================");
        System.out.println("  gRPC Benchmark (grpc-java client)");
        System.out.println("============================================");
        System.out.println("  server    : " + SERVER);
        System.out.println("  role      : " + ROLE);
        System.out.println("  threads   : " + THREADS);
        System.out.println("  warmup    : " + WARMUP_SECONDS + "s");
        System.out.println("  duration  : " + DURATION_SECONDS + "s");
        System.out.println("  port      : " + PORT);
        if (!"all".equals(ROLE)) {
            System.out.println("  host      : " + HOST);
        }
        System.out.println("  path      : /" + SERVICE_NAME + "/SayHello");
        System.out.println("============================================\n");

        // 1. Start the server side (skip for consumer role)
        if ("provider".equals(ROLE) || "all".equals(ROLE)) {
            if ("grpc".equals(SERVER)) {
                startGrpcServer();
            } else {
                exportWireService();
            }
        }
        if ("provider".equals(ROLE)) {
            System.out.println("Provider is ready at " + HOST + ":" + PORT
                    + ", waiting for consumer... (Ctrl+C to stop)");
            new CountDownLatch(1).await();
            return;
        }

        // 2. Client channel (real TCP loopback, h2c prior-knowledge)
        channel = NettyChannelBuilder.forAddress(HOST, PORT)
                .negotiationType(NegotiationType.PLAINTEXT)
                .build();

        // Verify invocation works
        HelloReply testReply = sayHello();
        if (!testReply.getMessage().contains(BENCHMARK_NAME)) {
            throw new RuntimeException("Sanity check failed: " + testReply.getMessage());
        }
        System.out.println("Sanity check passed: " + testReply.getMessage() + "\n");

        // 3. Warm-up
        System.out.println("Warming up (" + WARMUP_SECONDS + "s)...");
        runPhase(WARMUP_SECONDS, true);

        // 4. Measurement
        System.out.println("Measuring (" + DURATION_SECONDS + "s, " + THREADS + " threads)...");
        BenchmarkResult result = runPhase(DURATION_SECONDS, false);

        // 5. Print results
        printResult(result);

        long totalErrors = totalErrors();
        System.out.println("\n============================================");
        System.out.println("  Benchmark Done (" + (totalErrors == 0 ? "PASSED" : "FAILED") + ")");
        System.out.println("============================================");

        channel.shutdown();
        System.exit(totalErrors > 0 ? 1 : 0);
    }

    private static HelloReply sayHello() {
        return ClientCalls.blockingUnaryCall(channel, SAY_HELLO, CALL_OPTIONS, REQUEST);
    }

    private static void recordError(String type) {
        ERROR_COUNTERS.computeIfAbsent(type, k -> new LongAdder()).increment();
    }

    private static long totalErrors() {
        return ERROR_COUNTERS.values().stream().mapToLong(LongAdder::sum).sum();
    }

    private static void checkConfig() {
        if (!"wire".equals(SERVER) && !"grpc".equals(SERVER)) {
            throw new IllegalArgumentException("Invalid server: " + SERVER + ", expected wire / grpc");
        }
        boolean validRole = "all".equals(ROLE) || "provider".equals(ROLE) || "consumer".equals(ROLE);
        if (!validRole) {
            throw new IllegalArgumentException("Invalid role: " + ROLE + ", expected all / provider / consumer");
        }
    }

    private static void exportWireService() {
        ServiceConfig<GreeterService> serviceConfig = new ServiceConfig<>();
        serviceConfig.setRef(new JawsGreeterImpl());
        serviceConfig.setApplication("grpc-benchmark-provider");
        serviceConfig.setInterface(GreeterService.class);
        serviceConfig.setGroup("benchmark");
        serviceConfig.setVersion("1.0");
        serviceConfig.setProtocol(createProtocolConfig());
        serviceConfig.export();
    }

    private static ProtocolConfig createProtocolConfig() {
        ProtocolConfig protocol = new ProtocolConfig();
        protocol.setName("wire");
        protocol.setId("wire");
        protocol.setTransportFactory("wire");
        protocol.setPort(PORT);
        return protocol;
    }

    private static void startGrpcServer() throws Exception {
        ServerServiceDefinition definition = ServerServiceDefinition.builder(SERVICE_NAME)
                .addMethod(SAY_HELLO, ServerCalls.asyncUnaryCall(
                        (request, responseObserver) -> {
                            responseObserver.onNext(reply(request));
                            responseObserver.onCompleted();
                        }))
                .build();
        // Business pool mirroring the wire server's: bounded eager pool
        // (min20/max200/queue0) instead of the unbounded default cached pool,
        // so the reference measures the production-form thread model.
        EagerThreadPoolExecutor executor = new EagerThreadPoolExecutor(
                20, 200,
                EagerThreadPoolExecutor.DEFAULT_MAX_IDLE_TIME, TimeUnit.MILLISECONDS,
                0,
                new DefaultThreadFactory("grpc-benchmark-server", true),
                new AbortPolicyWithStats("grpc-benchmark-server"));
        executor.prestartAllCoreThreads();
        NettyServerBuilder.forPort(PORT)
                .executor(executor)
                .addService(definition)
                .build()
                .start();
        System.out.println("grpc-java server (netty, eager pool 20/200) started on port " + PORT);
    }

    private static HelloReply reply(HelloRequest request) {
        return HelloReply.newBuilder()
                .setMessage("Hello, " + request.getName() + "! (from " + SERVER + ")")
                .build();
    }

    private static class JawsGreeterImpl implements GreeterService {
        @Override
        public HelloReply sayHello(HelloRequest request) {
            return reply(request);
        }

        @Override
        public org.hongxi.jaws.stream.StreamSource<HelloReply> sayHelloStream(HelloRequest request) {
            throw new UnsupportedOperationException("Streaming not used in benchmark");
        }

        @Override
        public HelloReply clientStreamGreet(org.hongxi.jaws.stream.StreamSource<HelloRequest> names) {
            throw new UnsupportedOperationException("Streaming not used in benchmark");
        }

        @Override
        public org.hongxi.jaws.stream.StreamSource<HelloReply> bidiGreet(
                org.hongxi.jaws.stream.StreamSource<HelloRequest> names) {
            throw new UnsupportedOperationException("Streaming not used in benchmark");
        }
    }

    private static BenchmarkResult runPhase(int durationSeconds, boolean warmup)
            throws InterruptedException {
        AtomicLong totalCalls = new AtomicLong(0);
        List<List<Long>> perThreadLatencies = new ArrayList<>(THREADS);
        for (int i = 0; i < THREADS; i++) {
            perThreadLatencies.add(new ArrayList<>());
        }

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREADS);
        AtomicLong deadlineNanos = new AtomicLong(0);

        Thread[] workers = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            final List<Long> latencies = perThreadLatencies.get(i);
            workers[i] = new Thread(() -> {
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                long deadline = deadlineNanos.get();
                long calls = 0;
                while (System.nanoTime() < deadline) {
                    long start = System.nanoTime();
                    try {
                        HelloReply reply = sayHello();
                        if (!reply.getMessage().contains(BENCHMARK_NAME)) {
                            recordError("InvalidResponse");
                        } else if (!warmup) {
                            long elapsed = System.nanoTime() - start;
                            latencies.add(elapsed);
                        }
                        calls++;
                    } catch (Exception e) {
                        recordError(e.getClass().getSimpleName());
                    }
                }
                totalCalls.addAndGet(calls);
                doneLatch.countDown();
            }, "grpc-bench-worker-" + i);
            workers[i].setDaemon(true);
            workers[i].start();
        }

        deadlineNanos.set(System.nanoTime() + durationSeconds * 1_000_000_000L);
        startLatch.countDown();

        doneLatch.await();

        if (warmup) {
            System.out.println("Warmup done. Total calls: " + totalCalls.get());
            return null;
        }

        List<Long> allLatencies = new ArrayList<>();
        for (List<Long> list : perThreadLatencies) {
            allLatencies.addAll(list);
        }

        return new BenchmarkResult(allLatencies.size(), durationSeconds, allLatencies);
    }

    private static void printResult(BenchmarkResult result) {
        if (result == null || result.latencies().isEmpty()) {
            System.out.println("No data collected.");
            return;
        }

        long[] sorted = result.latencies().stream().mapToLong(Long::longValue).sorted().toArray();
        int count = sorted.length;

        long sum = 0;
        for (long v : sorted) {
            sum += v;
        }

        double qps = count / (double) result.durationSeconds();
        double avgUs = (sum / (double) count) / 1000.0;
        double minUs = sorted[0] / 1000.0;
        double maxUs = sorted[count - 1] / 1000.0;
        double p50Us = sorted[(int) (count * 0.50)] / 1000.0;
        double p90Us = sorted[(int) (count * 0.90)] / 1000.0;
        double p95Us = sorted[(int) (count * 0.95)] / 1000.0;
        double p99Us = sorted[(int) (count * 0.99)] / 1000.0;
        double p999Us = sorted[(int) (count * 0.999)] / 1000.0;

        System.out.println("\n--------------------------------------------");
        System.out.println("  Results");
        System.out.println("--------------------------------------------");
        System.out.println("  Protocol     : grpc (grpc-java client)");
        System.out.println("  Server       : " + ("grpc".equals(SERVER)
                ? "grpc-java (netty, eager pool, reference)" : "wire (interop)"));
        System.out.printf("  Threads      : %,d%n", THREADS);
        System.out.printf("  Total calls  : %,d%n", count);
        System.out.printf("  Duration     : %,ds%n", result.durationSeconds());
        System.out.printf("  QPS          : %,.0f%n", qps);
        System.out.println("--------------------------------------------");
        System.out.printf("  Min          : %,.2f us%n", minUs);
        System.out.printf("  Avg          : %,.2f us%n", avgUs);
        System.out.printf("  P50          : %,.2f us%n", p50Us);
        System.out.printf("  P90          : %,.2f us%n", p90Us);
        System.out.printf("  P95          : %,.2f us%n", p95Us);
        System.out.printf("  P99          : %,.2f us%n", p99Us);
        System.out.printf("  P99.9        : %,.2f us%n", p999Us);
        System.out.printf("  Max          : %,.2f us%n", maxUs);
        System.out.println("--------------------------------------------");

        long totalErrors = totalErrors();
        System.out.printf("  Errors       : %,d (warmup + measure)%n", totalErrors);
        ERROR_COUNTERS.forEach((type, counter) ->
                System.out.printf("    - %-28s %,d%n", type, counter.sum()));
        System.out.println("--------------------------------------------");
    }

    private record BenchmarkResult(int totalCalls, int durationSeconds, List<Long> latencies) {}
}
