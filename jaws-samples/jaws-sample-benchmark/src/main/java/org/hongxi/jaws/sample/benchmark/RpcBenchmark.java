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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Jaws RPC 性能基准测试
 *
 * <pre>
 * 支持两种协议：
 * - injvm：JVM 内部调用，测量框架纯开销
 * - jaws ：Netty 网络传输，测量端到端性能
 *
 * 系统属性参数（通过 -D 传入）：
 *   protocol  - 协议类型，injvm（默认）或 jaws
 *   threads   - 并发线程数，默认 4
 *   warmup    - 预热秒数，默认 5
 *   duration  - 测量秒数，默认 10
 *   port      - jaws 协议端口，默认 10010
 *   serialization - 序列化方式，默认 fastjson2（仅 jaws 协议生效）
 *   sleep       - Provider 端模拟业务耗时（毫秒），默认 0（不模拟）
 *
 * 示例：
 *   java -Dprotocol=injvm -Dthreads=8 -Dwarmup=5 -Dduration=10 ...
 *   java -Dprotocol=jaws -Dthreads=8 -Dport=10010 -Dserialization=hessian2 ...
 *   java -Dprotocol=jaws -Dthreads=8 -Dsleep=5 ...
 * </pre>
 */
public class RpcBenchmark {

    private static final String PROTOCOL = System.getProperty("protocol", "injvm");
    private static final int THREADS = Integer.parseInt(System.getProperty("threads", "4"));
    private static final int WARMUP_SECONDS = Integer.parseInt(System.getProperty("warmup", "5"));
    private static final int DURATION_SECONDS = Integer.parseInt(System.getProperty("duration", "10"));
    private static final int PORT = Integer.parseInt(System.getProperty("port", "10010"));
    private static final String SERIALIZATION = System.getProperty("serialization", "fastjson2");
    private static final int SLEEP_MS = Integer.parseInt(System.getProperty("sleep", "0"));

    private static final String BENCHMARK_RESULT = "benchmark";

    public static void main(String[] args) throws Exception {
        System.out.println("============================================");
        System.out.println("  Jaws RPC Benchmark");
        System.out.println("============================================");
        System.out.println("  protocol : " + PROTOCOL);
        System.out.println("  threads  : " + THREADS);
        System.out.println("  warmup   : " + WARMUP_SECONDS + "s");
        System.out.println("  duration : " + DURATION_SECONDS + "s");
        System.out.println("  sleep    : " + (SLEEP_MS > 0 ? SLEEP_MS + "ms" : "N/A"));
        if ("jaws".equals(PROTOCOL)) {
            System.out.println("  port     : " + PORT);
            System.out.println("  serialize: " + SERIALIZATION);
        }
        System.out.println("============================================\n");

        // 1. 发布服务
        exportService();

        // 2. 创建引用
        ReferenceConfig<DemoService> ref = createReference();
        DemoService demoService = ref.getRef();

        // 验证调用正常
        String testResult = demoService.hello(BENCHMARK_RESULT);
        if (!testResult.contains(BENCHMARK_RESULT)) {
            throw new RuntimeException("Sanity check failed: " + testResult);
        }
        System.out.println("Sanity check passed: " + testResult + "\n");

        // 3. 预热
        System.out.println("Warming up (" + WARMUP_SECONDS + "s)...");
        runPhase(demoService, WARMUP_SECONDS, true);

        // 4. 正式测量
        System.out.println("Measuring (" + DURATION_SECONDS + "s, " + THREADS + " threads)...");
        BenchmarkResult result = runPhase(demoService, DURATION_SECONDS, false);

        // 5. 输出结果
        printResult(result);

        System.out.println("\n============================================");
        System.out.println("  Benchmark Done");
        System.out.println("============================================");

        /* 基准测试完毕，强制退出（Netty/Curator 的非守护线程会阻止 JVM 自动退出） */
        System.exit(0);
    }

    /*
     * 发布 DemoService
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
        serviceConfig.setRegistry(createRegistryConfig());

        if ("jaws".equals(PROTOCOL)) {
            serviceConfig.setExport(JawsConstants.PROTOCOL_JAWS + ":" + PORT);
        } else {
            serviceConfig.setExport(JawsConstants.PROTOCOL_INJVM + ":0");
        }

        serviceConfig.export();
        System.out.println("DemoService exported (" + PROTOCOL + ").");
    }

    /*
     * 创建 ReferenceConfig
     */
    private static ReferenceConfig<DemoService> createReference() {
        ReferenceConfig<DemoService> ref = new ReferenceConfig<>();
        ref.setInterface(DemoService.class);
        ref.setApplication("benchmark-consumer");
        ref.setGroup("benchmark");
        ref.setVersion("1.0");
        ref.setProtocol(createProtocolConfig());
        ref.setRegistry(createRegistryConfig());
        ref.setRequestTimeout(30000);
        return ref;
    }

    private static ProtocolConfig createProtocolConfig() {
        ProtocolConfig protocol = new ProtocolConfig();
        protocol.setName(PROTOCOL);
        protocol.setId(PROTOCOL);
        if ("jaws".equals(PROTOCOL)) {
            protocol.setEndpointFactory("netty");
            protocol.setSerialization(SERIALIZATION);
        }
        return protocol;
    }

    private static RegistryConfig createRegistryConfig() {
        RegistryConfig registry = new RegistryConfig();
        registry.setProtocol(JawsConstants.REGISTRY_PROTOCOL_LOCAL);
        registry.setName("localRegistry");
        registry.setId("localRegistry");
        registry.setAddress("127.0.0.1");
        registry.setPort(0);
        return registry;
    }

    /*
     * 运行一个测试阶段（预热或测量）
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
                        demoService.hello(BENCHMARK_RESULT);
                        if (!warmup) {
                            long elapsed = System.nanoTime() - start;
                            latencies.add(elapsed);
                        }
                        calls++;
                    } catch (Exception e) {
                        // 忽略单次调用异常，继续压测
                    }
                }
                totalCalls.addAndGet(calls);
                doneLatch.countDown();
            }, "bench-worker-" + i);
            workers[i].setDaemon(true);
            workers[i].start();
        }

        // 设定截止时间并释放起跑信号
        deadlineNanos.set(System.nanoTime() + durationSeconds * 1_000_000_000L);
        startLatch.countDown();

        // 等待所有线程完成
        doneLatch.await();

        if (warmup) {
            System.out.println("Warmup done. Total calls: " + totalCalls.get());
            return null;
        }

        // 合并所有线程的延迟数据
        List<Long> allLatencies = new ArrayList<>();
        for (List<Long> list : perThreadLatencies) {
            allLatencies.addAll(list);
        }

        return new BenchmarkResult(allLatencies.size(), durationSeconds, allLatencies);
    }

    /*
     * 打印统计结果
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
    }

    private record BenchmarkResult(int totalCalls, int durationSeconds, List<Long> latencies) {}
}
