package org.hongxi.jaws.sample.benchmark;

import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.sample.wire.proto.GreeterService;
import org.hongxi.jaws.sample.wire.proto.HelloReply;
import org.hongxi.jaws.sample.wire.proto.HelloRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Wire (gRPC wire format) performance benchmark.
 *
 * <p>Same structure as {@link JawsBenchmark}, but uses protobuf GreeterService
 * and the wire protocol (gRPC 5-byte-prefix wire format over HTTP/2).
 *
 * <pre>
 * System properties (passed via -D):
 *   role        - Run role: all (default, same process) / provider / consumer
 *   threads     - Concurrency thread count, default 4 (only for all / consumer)
 *   warmup      - Warm-up seconds, default 5
 *   duration    - Measurement seconds, default 10
 *   port        - Wire protocol port, default 50051
 *   host        - Provider address, consumer direct-connect target, default 127.0.0.1 (separate-process mode only)
 *   compression - Compression method, default empty (none), optional: gzip
 *
 * Examples:
 *   java -Dthreads=20 -Dduration=40 ...
 *   # Separate processes: start provider first, then run consumer benchmark
 *   java -Drole=provider -Dport=50051 ...
 *   java -Drole=consumer -Dport=50051 -Dthreads=20 ...
 * </pre>
 */
public class WireBenchmark {

    private static final String ROLE = System.getProperty("role", "all");
    private static final int THREADS = Integer.parseInt(System.getProperty("threads", "4"));
    private static final int WARMUP_SECONDS = Integer.parseInt(System.getProperty("warmup", "5"));
    private static final int DURATION_SECONDS = Integer.parseInt(System.getProperty("duration", "10"));
    private static final int PORT = Integer.parseInt(System.getProperty("port", "50051"));
    private static final String HOST = System.getProperty("host", "127.0.0.1");
    private static final String COMPRESSION = System.getProperty("compression", "");

    private static final String BENCHMARK_NAME = "benchmark";
    private static final HelloRequest REQUEST =
            HelloRequest.newBuilder().setName(BENCHMARK_NAME).build();

    /**
     * Error counters keyed by error type (exception simple name or "InvalidResponse"),
     * accumulated across both warmup and measurement phases.
     */
    private static final ConcurrentMap<String, LongAdder> ERROR_COUNTERS = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        checkRole();

        System.out.println("============================================");
        System.out.println("  Jaws Wire Benchmark");
        System.out.println("============================================");
        System.out.println("  role      : " + ROLE);
        System.out.println("  threads   : " + THREADS);
        System.out.println("  warmup    : " + WARMUP_SECONDS + "s");
        System.out.println("  duration  : " + DURATION_SECONDS + "s");
        System.out.println("  port      : " + PORT);
        System.out.println("  compress  : " + (COMPRESSION.isEmpty() ? "none" : COMPRESSION));
        if (!"all".equals(ROLE)) {
            System.out.println("  host      : " + HOST);
        }
        System.out.println("============================================\n");

        // 1. Export service (skip for consumer role)
        if (!"consumer".equals(ROLE)) {
            exportService();
        }

        // provider role: export service then block
        if ("provider".equals(ROLE)) {
            System.out.println("Provider is ready at " + HOST + ":" + PORT
                    + ", waiting for consumer... (Ctrl+C to stop)");
            new CountDownLatch(1).await();
            return;
        }

        // 2. Create reference
        ReferenceConfig<GreeterService> ref = createReference();
        GreeterService greeterService = ref.getRef();

        // Verify invocation works
        HelloReply testReply = greeterService.sayHello(REQUEST);
        if (!testReply.getMessage().contains(BENCHMARK_NAME)) {
            throw new RuntimeException("Sanity check failed: " + testReply.getMessage());
        }
        System.out.println("Sanity check passed: " + testReply.getMessage() + "\n");

        // 3. Warm-up
        System.out.println("Warming up (" + WARMUP_SECONDS + "s)...");
        runPhase(greeterService, WARMUP_SECONDS, true);

        // 4. Measurement
        System.out.println("Measuring (" + DURATION_SECONDS + "s, " + THREADS + " threads)...");
        BenchmarkResult result = runPhase(greeterService, DURATION_SECONDS, false);

        // 5. Print results
        printResult(result);

        long totalErrors = totalErrors();
        System.out.println("\n============================================");
        System.out.println("  Benchmark Done (" + (totalErrors == 0 ? "PASSED" : "FAILED") + ")");
        System.out.println("============================================");

        System.exit(totalErrors > 0 ? 1 : 0);
    }

    private static void recordError(String type) {
        ERROR_COUNTERS.computeIfAbsent(type, k -> new LongAdder()).increment();
    }

    private static long totalErrors() {
        return ERROR_COUNTERS.values().stream().mapToLong(LongAdder::sum).sum();
    }

    private static void exportService() {
        ServiceConfig<GreeterService> serviceConfig = new ServiceConfig<>();
        serviceConfig.setRef(new GreeterServiceImpl());
        serviceConfig.setApplication("wire-benchmark-provider");
        serviceConfig.setInterface(GreeterService.class);
        serviceConfig.setGroup("benchmark");
        serviceConfig.setVersion("1.0");
        serviceConfig.setProtocol(createProtocolConfig());
        serviceConfig.export();
    }

    private static ReferenceConfig<GreeterService> createReference() {
        ReferenceConfig<GreeterService> ref = new ReferenceConfig<>();
        ref.setInterface(GreeterService.class);
        ref.setApplication("wire-benchmark-consumer");
        ref.setGroup("benchmark");
        ref.setVersion("1.0");
        ref.setProtocol(createProtocolConfig());
        ref.setDirectUrl(HOST + ":" + PORT);
        ref.setRequestTimeout(30000);
        return ref;
    }

    private static ProtocolConfig createProtocolConfig() {
        ProtocolConfig protocol = new ProtocolConfig();
        protocol.setName("wire");
        protocol.setId("wire");
        protocol.setTransportFactory("wire");
        protocol.setPort(PORT);
        if (!COMPRESSION.isEmpty()) {
            protocol.setCompression(COMPRESSION);
        }
        return protocol;
    }

    private static void checkRole() {
        boolean validRole = "all".equals(ROLE) || "provider".equals(ROLE) || "consumer".equals(ROLE);
        if (!validRole) {
            throw new IllegalArgumentException("Invalid role: " + ROLE + ", expected all / provider / consumer");
        }
    }

    /**
     * Simple GreeterService implementation for benchmarking.
     * Returns "Hello, {name}! (from wire)" without any logging overhead.
     */
    private static class GreeterServiceImpl implements GreeterService {
        @Override
        public HelloReply sayHello(HelloRequest request) {
            return HelloReply.newBuilder()
                    .setMessage("Hello, " + request.getName() + "! (from wire)")
                    .build();
        }

        @Override
        public java.util.concurrent.Flow.Publisher<HelloReply> sayHelloStream(HelloRequest request) {
            throw new UnsupportedOperationException("Streaming not used in benchmark");
        }
    }

    private static BenchmarkResult runPhase(GreeterService greeterService, int durationSeconds, boolean warmup)
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
            final int threadIndex = i;
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
                        HelloReply reply = greeterService.sayHello(REQUEST);
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
            }, "wire-bench-worker-" + i);
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
        System.out.println("  Protocol     : wire");
        System.out.println("  Transport    : wire (gRPC wire format)");
        System.out.println("  Compression  : " + (COMPRESSION.isEmpty() ? "none" : COMPRESSION));
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
