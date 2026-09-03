package org.hongxi.jaws.sample.benchmark;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.injvm.service.DemoServiceImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Jaws RPC performance benchmark.
 *
 * <pre>
 * Supported protocols:
 * - injvm: in-JVM invocation, measures pure framework overhead
 * - jaws: Netty network transport, measures end-to-end performance
 *
 * Deployment modes:
 * - role=all (default): provider + consumer in the same process, using in-process local registry
 * - role=provider / role=consumer: separate processes, consumer uses setDirectUrl
 *   to connect directly to provider's host:port (no external registry needed)
 *
 * System properties (passed via -D):
 *   protocol      - Protocol type: injvm (default) or jaws
 *   role          - Run role: all (default, same process) / provider / consumer (jaws protocol only)
 *   threads       - Concurrency thread count, default 4 (only for all / consumer)
 *   warmup        - Warm-up seconds, default 5 (only for all / consumer)
 *   duration      - Measurement seconds, default 10 (only for all / consumer)
 *   port          - jaws protocol port, default 10010
 *   host          - Provider address, consumer direct-connect target, default 127.0.0.1 (separate-process mode only)
 *   serialization - Serialization method, default fastjson2 (jaws protocol only)
 *   transport     - Transport layer: netty (default TCP) or http2 (jaws protocol only)
 *   sleep         - Simulated business processing time on provider side (ms), default 0 (no simulation)
 *
 * Examples:
 *   java -Dprotocol=injvm -Dthreads=8 -Dwarmup=5 -Dduration=10 ...
 *   java -Dprotocol=jaws -Dthreads=8 -Dport=10010 -Dserialization=hessian2 ...
 *   java -Dprotocol=jaws -Dtransport=http2 -Dthreads=20 -Dduration=40 ...
 *   java -Dprotocol=jaws -Dthreads=8 -Dsleep=5 ...
 *   # Separate processes: start provider first, then run consumer benchmark
 *   java -Dprotocol=jaws -Drole=provider -Dport=10010 ...
 *   java -Dprotocol=jaws -Drole=consumer -Dport=10010 -Dthreads=20 ...
 * </pre>
 */
public class JawsBenchmark {

    private static final String PROTOCOL = System.getProperty("protocol", "injvm");
    private static final String ROLE = System.getProperty("role", "all");
    private static final int THREADS = Integer.parseInt(System.getProperty("threads", "4"));
    private static final int WARMUP_SECONDS = Integer.parseInt(System.getProperty("warmup", "5"));
    private static final int DURATION_SECONDS = Integer.parseInt(System.getProperty("duration", "10"));
    private static final int PORT = Integer.parseInt(System.getProperty("port", "10010"));
    private static final String HOST = System.getProperty("host", "127.0.0.1");
    private static final String SERIALIZATION = System.getProperty("serialization", "fastjson2");
    private static final String TRANSPORT = System.getProperty("transport", "netty");
    private static final int SLEEP_MS = Integer.parseInt(System.getProperty("sleep", "0"));

    private static final String BENCHMARK_RESULT = "benchmark";
    private static final String EXPECTED_RESULT = "Hello, " + BENCHMARK_RESULT;

    /**
     * Error counters keyed by error type (exception simple name or "InvalidResponse"),
     * accumulated across both warmup and measurement phases.
     */
    private static final ConcurrentMap<String, LongAdder> ERROR_COUNTERS = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        checkRole();

        System.out.println("============================================");
        System.out.println("  Jaws RPC Benchmark");
        System.out.println("============================================");
        System.out.println("  protocol : " + PROTOCOL);
        System.out.println("  role     : " + ROLE);
        System.out.println("  threads  : " + THREADS);
        System.out.println("  warmup   : " + WARMUP_SECONDS + "s");
        System.out.println("  duration : " + DURATION_SECONDS + "s");
        System.out.println("  sleep    : " + (SLEEP_MS > 0 ? SLEEP_MS + "ms" : "N/A"));
        if ("jaws".equals(PROTOCOL)) {
            System.out.println("  port     : " + PORT);
            System.out.println("  serialize: " + SERIALIZATION);
            System.out.println("  transport: " + TRANSPORT);
            if (!"all".equals(ROLE)) {
                System.out.println("  host     : " + HOST);
            }
        }
        System.out.println("============================================\n");

        // 1. Export service (skip for consumer role)
        if (!"consumer".equals(ROLE)) {
            exportService();
        }

        // provider role: export service then block, benchmark is driven by a separate consumer process
        if ("provider".equals(ROLE)) {
            System.out.println("Provider is ready at " + HOST + ":" + PORT
                    + ", waiting for consumer... (Ctrl+C to stop)");
            new CountDownLatch(1).await();
            return;
        }

        // 2. Create reference
        ReferenceConfig<DemoService> ref = createReference();
        DemoService demoService = ref.getRef();

        // Verify invocation works
        String testResult = demoService.hello(BENCHMARK_RESULT);
        if (!testResult.contains(BENCHMARK_RESULT)) {
            throw new RuntimeException("Sanity check failed: " + testResult);
        }
        System.out.println("Sanity check passed: " + testResult + "\n");

        // 3. Warm-up
        System.out.println("Warming up (" + WARMUP_SECONDS + "s)...");
        runPhase(demoService, WARMUP_SECONDS, true);

        // 4. Measurement
        System.out.println("Measuring (" + DURATION_SECONDS + "s, " + THREADS + " threads)...");
        BenchmarkResult result = runPhase(demoService, DURATION_SECONDS, false);

        // 5. Print results
        printResult(result);

        long totalErrors = totalErrors();
        System.out.println("\n============================================");
        System.out.println("  Benchmark Done (" + (totalErrors == 0 ? "PASSED" : "FAILED") + ")");
        System.out.println("============================================");

        /* Force exit after benchmark (non-daemon threads from Netty/Curator would prevent JVM shutdown) */
        System.exit(totalErrors > 0 ? 1 : 0);
    }

    private static void recordError(String type) {
        ERROR_COUNTERS.computeIfAbsent(type, k -> new LongAdder()).increment();
    }

    private static long totalErrors() {
        return ERROR_COUNTERS.values().stream().mapToLong(LongAdder::sum).sum();
    }

    /*
     * Export DemoService
     */
    private static void exportService() {
        ServiceConfig<DemoService> serviceConfig = new ServiceConfig<>();
        DemoService impl = new DemoServiceImpl();
        if (SLEEP_MS > 0) {
            impl = new SleepDemoServiceImpl(impl, SLEEP_MS);
        }
        serviceConfig.setRef(impl);
        serviceConfig.setApplication("benchmark-provider");
        serviceConfig.setInterface(DemoService.class);
        serviceConfig.setGroup("benchmark");
        serviceConfig.setVersion("1.0");
        serviceConfig.setProtocol(createProtocolConfig());
        if ("all".equals(ROLE)) {
            serviceConfig.setRegistry(createRegistryConfig());
        }
        serviceConfig.export();
    }

    /*
     * Create ReferenceConfig
     */
    private static ReferenceConfig<DemoService> createReference() {
        ReferenceConfig<DemoService> ref = new ReferenceConfig<>();
        ref.setInterface(DemoService.class);
        ref.setApplication("benchmark-consumer");
        ref.setGroup("benchmark");
        ref.setVersion("1.0");
        ref.setProtocol(createProtocolConfig());
        if (!"all".equals(ROLE)) {
            ref.setDirectUrl(HOST + ":" + PORT);
        } else {
            ref.setRegistry(createRegistryConfig());
        }
        ref.setRequestTimeout(30000);
        return ref;
    }

    private static ProtocolConfig createProtocolConfig() {
        ProtocolConfig protocol = new ProtocolConfig();
        protocol.setName(PROTOCOL);
        protocol.setId(PROTOCOL);
        if ("jaws".equals(PROTOCOL)) {
            protocol.setTransportFactory(TRANSPORT);
            protocol.setSerialization(SERIALIZATION);
            protocol.setPort(PORT);
        }
        return protocol;
    }

    private static RegistryConfig createRegistryConfig() {
        RegistryConfig registry = new RegistryConfig();
        registry.setId("benchmarkRegistry");
        registry.setProtocol(JawsConstants.REGISTRY_PROTOCOL_LOCAL);
        registry.setAddress("127.0.0.1");
        registry.setPort(0);
        return registry;
    }

    private static void checkRole() {
        boolean validRole = "all".equals(ROLE) || "provider".equals(ROLE) || "consumer".equals(ROLE);
        if (!validRole) {
            throw new IllegalArgumentException("Invalid role: " + ROLE + ", expected all / provider / consumer");
        }
        if (!"all".equals(ROLE) && !"jaws".equals(PROTOCOL)) {
            throw new IllegalArgumentException("role=" + ROLE + " requires protocol=jaws, injvm does not work cross-process");
        }
    }

    /*
     * Run a test phase (warm-up or measurement)
     */
    private static BenchmarkResult runPhase(DemoService demoService, int durationSeconds, boolean warmup)
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
                        String result = demoService.hello(BENCHMARK_RESULT);
                        if (!EXPECTED_RESULT.equals(result)) {
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
            }, "bench-worker-" + i);
            workers[i].setDaemon(true);
            workers[i].start();
        }

        // Set deadline and release the start signal
        deadlineNanos.set(System.nanoTime() + durationSeconds * 1_000_000_000L);
        startLatch.countDown();

        // Wait for all threads to finish
        doneLatch.await();

        if (warmup) {
            System.out.println("Warmup done. Total calls: " + totalCalls.get());
            return null;
        }

        // Merge latency data from all threads
        List<Long> allLatencies = new ArrayList<>();
        for (List<Long> list : perThreadLatencies) {
            allLatencies.addAll(list);
        }

        return new BenchmarkResult(allLatencies.size(), durationSeconds, allLatencies);
    }

    /*
     * Print statistics
     */
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
        System.out.println("  Protocol     : " + PROTOCOL);
        System.out.println("  Serialization: " + ("jaws".equals(PROTOCOL) ? SERIALIZATION : "N/A"));
        System.out.println("  Transport    : " + ("jaws".equals(PROTOCOL) ? TRANSPORT : "N/A"));
        System.out.printf("  Threads      : %,d%n", THREADS);
        System.out.println("  Sleep        : " + (SLEEP_MS > 0 ? SLEEP_MS + "ms" : "N/A"));
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
