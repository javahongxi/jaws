package org.hongxi.jaws.sample.store;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.store.ConsumeQueue;
import org.hongxi.jaws.store.MessageCodec;
import org.hongxi.jaws.store.OffsetResult;
import org.hongxi.jaws.store.PutResult;
import org.hongxi.jaws.store.StoreBroker;
import org.hongxi.jaws.store.StoreConfig;
import org.hongxi.jaws.store.StoreMessage;
import org.hongxi.jaws.store.StoreService;
import org.hongxi.jaws.store.client.StoreConsumer;
import org.hongxi.jaws.store.client.StoreProducer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

/**
 * Closed-loop throughput benchmark for the store's client layer: producers drive
 * {@link StoreProducer} and consumers run on {@link StoreConsumer}, both talking to a
 * {@link StoreBroker} over the real jaws RPC protocol. Unlike {@link StoreBenchmark},
 * which bypasses the wire to measure the raw storage kernel, this one keeps the whole
 * pipeline in the picture — connection EventLoop, serialization, long-polling hold and
 * lease/offset machinery — so the question it answers is "does the client-side
 * consumption keep pace with extreme production", not "how fast can the engine memcpy".
 *
 * <p>Method mirrors the kernel bench: an OPEN-loop staged ramp. Each stage offers a
 * fixed target rate (msg/s); producers fire {@code sendAsync} (or {@code sendOneway})
 * and never wait on the consumer, so "the pipeline is slow" surfaces as backlog growth
 * and send failures, never as the load generator throttling itself. Consumption keeps
 * up while backlog stays flat at a stage; the highest such stage is the ceiling. The
 * final target=0 stage free-runs to find the write-side RPC maximum.
 *
 * <p>Roles via {@code -Drole}:
 * <ul>
 *   <li>{@code all} (default) — broker, producers and consumers share one JVM. Easiest
 *       to run, but broker/producer/consumer contend for the same CPU and GC, so the
 *       ceiling reads pessimistically (the same effect benchmark.md measured for the RPC
 *       bench: split-process beats in-process).</li>
 *   <li>{@code broker} — start a {@link StoreBroker} with the bench store config and
 *       block; run producers and consumers against it from other processes.</li>
 *   <li>{@code producer} — open-loop staircase only. Reports the production side
 *       (offered / accepted / failed / send latency) per stage.</li>
 *   <li>{@code consumer} — run the {@link StoreConsumer} instances and continuously
 *       measure the consumption side against the broker's own offsets: production
 *       frontier = sum of per-queue {@code maxOffset}, consumption frontier = sum of the
 *       group's committed offsets, backlog = the gap. This is authoritative and needs no
 *       counter shared with the producer process — the gap directly shows whether the
 *       consumer keeps up.</li>
 * </ul>
 *
 * <p>The write side is a multi-connection leg: {@code -Dproducers} producer threads each
 * own one {@link StoreProducer} (a dedicated broker connection / write EventLoop). The
 * consumer side is a SINGLE {@link StoreConsumer} in one Exclusive group subscribing every
 * queue, so the queue count IS the read parallelism: the consumer runs one pump per queue,
 * giving {@code queues} concurrent in-flight pulls on its one broker connection. Scaling
 * read parallelism past that means raising {@code -Dqueues}, not running a second same-group
 * instance — the broker's lease fence rejects that. {@code -Dqueues} defaults to 8, matching
 * {@link StoreBenchmark}.
 *
 * <p>Disk safety mirrors {@link StoreBenchmark}: the broker is configured with a short
 * retention + fast cleanup + a {@code diskMaxUsedSpaceRatio} write refusal, and in the
 * {@code all}/{@code producer} roles (where the volume is locally visible) the sampler
 * additionally hard-stops producers at {@code -DdiskStopRatio}.
 *
 * <p>Not a unit test: no JUnit annotations, excluded from CI; run it as a main.
 *
 * <pre>
 * Usage (or via run-sample.sh, see below):
 *   java -cp ... org.hongxi.jaws.sample.store.StorePipelineBenchmark \
 *     -Drole=all|broker|producer|consumer [-Dhost=127.0.0.1] [-Dport=10911] \
 *     [-DstorePath=/tmp/jaws-store-bench-rpc] [-DmsgSize=128] [-Dqueues=8] \
 *     [-Dproducers=16] [-Dgroup=bench-group] [-DsendMode=async|oneway] [-DmaxInflight=512] \
 *     [-DpullBatch=32] [-DpullThreshold=1000] [-DsuspendTimeout=15000] \
 *     [-Dsteps=50000,100000,200000,400000,600000,800000,0] \
 *     [-DstepSeconds=30] [-DfinalStepSeconds=60] \
 *     [-DretentionMillis=60000] [-DcleanupIntervalMillis=3000] \
 *     [-DdiskRatio=75] [-DdiskStopRatio=85] [-DcleanupKeepFiles=2]
 * </pre>
 *
 * <pre>
 * Split-process run (three terminals, same host):
 *   ROLE=broker   ./run-sample.sh bench-store-rpc
 *   ROLE=consumer ./run-sample.sh bench-store-rpc     # starts before producers
 *   ROLE=producer ./run-sample.sh bench-store-rpc     # drives the staircase
 * </pre>
 */
public final class StorePipelineBenchmark {

    private static final String TOPIC = "bench-topic";
    private static final int MAX_STEPS = 16;
    /** Sends slower than this are counted as stalls (broker worker-queue backlog
     * under the write lock is the expected source). */
    private static final long STALL_NANOS = 10_000_000L;
    /** Sample interval for the consumer-role offset-frontier monitor. */
    private static final long CONSUMER_SAMPLE_MS = 1000L;

    private static final class Conf {
        final String role = System.getProperty("role", "all");
        final String host = System.getProperty("host", "127.0.0.1");
        final String storePath = System.getProperty("storePath", System.getProperty("user.home") + "/jaws-store-bench-rpc");
        final int port = Integer.getInteger("port", 10911);
        final int msgSize = Integer.getInteger("msgSize", 128);
        final int queues = Integer.getInteger("queues", 8);
        final int producers = Integer.getInteger("producers", 16);
        // Exclusive model: one reader per queue, so a second same-group instance would
        // fail its lease claim. Read parallelism comes from the queue count, not instance
        // count — one StoreConsumer subscribes every queue (pumps = queues).
        final String group = System.getProperty("group", "bench-group");
        final String sendMode = System.getProperty("sendMode", "async");
        final int maxInflight = Integer.getInteger("maxInflight", StoreProducer.DEFAULT_MAX_INFLIGHT);
        final int requestTimeout = Integer.getInteger("requestTimeout", 3000);
        final String serialization = System.getProperty("serialization", "fastjson2");
        final int pullBatch = Integer.getInteger("pullBatch", 32);
        final int pullThreshold = Integer.getInteger("pullThreshold", 1000);
        final long suspendTimeout = Long.getLong("suspendTimeout", 15_000L);
        final int stepSeconds = Integer.getInteger("stepSeconds", 30);
        final int finalStepSeconds = Integer.getInteger("finalStepSeconds", 60);
        final long retentionMillis = Long.getLong("retentionMillis", 60_000L);
        final int cleanupIntervalMillis = Integer.getInteger("cleanupIntervalMillis", 3_000);
        final int diskRatio = Integer.getInteger("diskRatio", 75);
        final int diskStopRatio = Integer.getInteger("diskStopRatio", 85);
        final int cleanupKeepFiles = Integer.getInteger("cleanupKeepFiles", 2);
        // Open-loop staircase: 50k and up, one extra rung past the ~50k/s the first
        // single-consumer smoke showed falling behind, so the run brackets the
        // consumption ceiling instead of stopping below it; 0 = free-run for the write cap.
        final int[] steps = parseSteps(System.getProperty("steps",
                "50000,100000,200000,400000,600000,800000,0"));

        static int[] parseSteps(String raw) {
            String[] parts = raw.split(",");
            int[] out = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                out[i] = Integer.parseInt(parts[i].trim());
            }
            return out;
        }
    }

    /** One in-process sampler line; step boundaries located by timestamp + stepIndex. */
    private static final class Snapshot {
        final long atMillis;
        final long accepted, consumed;
        final long backlog;
        final double diskRatio;
        final int stepIndex;

        Snapshot(long atMillis, long accepted, long consumed, long backlog, double diskRatio, int stepIndex) {
            this.atMillis = atMillis;
            this.accepted = accepted;
            this.consumed = consumed;
            this.backlog = backlog;
            this.diskRatio = diskRatio;
            this.stepIndex = stepIndex;
        }
    }

    private final Conf conf;
    private final String directUrl;

    // producer-side counters (cumulative; per-step values are windowed diffs)
    private final AtomicLong offered = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong failedSend = new AtomicLong();
    private final AtomicLongArray latSumNs = new AtomicLongArray(MAX_STEPS);
    private final AtomicLongArray latCount = new AtomicLongArray(MAX_STEPS);
    private final AtomicLongArray latMaxNs = new AtomicLongArray(MAX_STEPS);
    private final AtomicLongArray stallCount = new AtomicLongArray(MAX_STEPS);

    // consumer-side counter, fed by every StoreConsumer listener (in-process roles only)
    private final AtomicLong consumed = new AtomicLong();

    private final ConcurrentLinkedQueue<Snapshot> snapshots = new ConcurrentLinkedQueue<>();
    private final AtomicLong currentStep = new AtomicLong(-1);
    private volatile boolean stopProducing;
    private volatile boolean running = true;

    private StorePipelineBenchmark(Conf conf) {
        this.conf = conf;
        this.directUrl = conf.host + ":" + conf.port;
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        Conf conf = new Conf();
        switch (conf.role) {
            case "broker" -> runBrokerOnly(conf);
            case "producer" -> new StorePipelineBenchmark(conf).runProducerOnly();
            case "consumer" -> new StorePipelineBenchmark(conf).runConsumerOnly();
            default -> runAll(conf);
        }
    }

    /** Full pipeline in one JVM: broker + producer threads + the single consumer. */
    private static void runAll(Conf conf) throws Exception {
        wipeDir(conf.storePath);
        StoreBroker broker = startBroker(conf);

        StorePipelineBenchmark bench = new StorePipelineBenchmark(conf);
        List<StoreConsumer> consumers = bench.startConsumers();
        Thread sampler = bench.newSamplerThread(true);

        // Give the consumers a moment to attach, claim leases and park their pumps in
        // the long-polling hold before the first message arrives, so the ramp exercises
        // the wake-on-write path rather than racing an un-attached group.
        Thread.sleep(2000);

        List<StoreProducer> producers = bench.startProducers();
        List<String> report = bench.runStages(producers);
        bench.running = false;
        for (StoreProducer producer : producers) {
            producer.shutdown();
        }
        sampler.join(5000);
        for (StoreConsumer consumer : consumers) {
            consumer.shutdown();
        }
        report.forEach(System.out::println);
        broker.shutdown();
        System.out.println("Done. Data left under " + conf.storePath);
    }

    /** Broker-only role: own the data dir, start the engine + RPC, block until killed. */
    private static void runBrokerOnly(Conf conf) throws Exception {
        wipeDir(conf.storePath);
        StoreBroker broker = startBroker(conf);
        System.out.println("StoreBroker (bench) on " + conf.host + ":" + conf.port
                + ", storePath=" + conf.storePath
                + " — run ROLE=consumer then ROLE=producer against it. Ctrl+C to stop.");
        Runtime.getRuntime().addShutdownHook(new Thread(broker::shutdown, "StoreBrokerShutdown"));
        new CountDownLatch(1).await();
    }

    /**
     * Producer-only role: drive the open-loop staircase against an already-running
     * broker and report the production side. No local consumption counter, so backlog
     * is left to the consumer process; the disk guard only engages when the broker's
     * volume is locally visible.
     */
    private void runProducerOnly() throws Exception {
        System.out.println("=== jaws-store RPC closed-loop benchmark [producer] ===");
        printBanner(conf, directUrl);
        boolean diskVisible = new File(conf.storePath, "commitlog").isDirectory();
        Thread sampler = newSamplerThread(false);
        List<StoreProducer> producers = startProducers();
        List<String> report = runStages(producers);
        running = false;
        for (StoreProducer producer : producers) {
            producer.shutdown();
        }
        if (diskVisible) {
            sampler.join(5000);
        }
        report.forEach(System.out::println);
        System.out.println("Producer stages complete. Read the consumer process for the keep-up verdict.");
    }

    /**
     * Consumer-only role: run the single StoreConsumer (one group, one pump per queue) and
     * continuously compare the broker's production frontier (sum of per-queue
     * {@code maxOffset}) against the group's consumption frontier (sum of committed
     * offsets). Backlog is the gap; it stays flat while consumption keeps up and grows the
     * moment production outruns it.
     */
    private void runConsumerOnly() throws Exception {
        System.out.println("=== jaws-store RPC closed-loop benchmark [consumer] ===");
        printBanner(conf, directUrl);
        List<StoreConsumer> consumers = startConsumers();
        ReferenceConfig<StoreService> query = openQueryReference();
        StoreService svc = query.getRef();

        long totalSec = 0;
        for (int s : conf.steps) {
            totalSec += s <= 0 ? conf.finalStepSeconds : conf.stepSeconds;
        }
        // Small grace so the monitor outlives the last producer stage and drains visibly.
        long deadline = System.currentTimeMillis() + (totalSec + 15) * 1000L;
        System.out.printf("%-8s %12s %12s %14s%n", "t+s", "prodRate/s", "consRate/s", "backlog");
        long prevMax = 0, prevCommit = 0, prevMs = System.currentTimeMillis();
        final long runStart = prevMs;
        boolean primed = false;
        while (System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(CONSUMER_SAMPLE_MS * 1_000_000L);
            long sumMax = 0, sumCommit = 0;
            boolean roundOk = true;
            for (int q = 0; q < conf.queues; q++) {
                try {
                    Long max = svc.maxOffsetAsync(TOPIC, q).get(5, TimeUnit.SECONDS);
                    OffsetResult commit = svc.queryOffsetAsync(conf.group, TOPIC, q).get(5, TimeUnit.SECONDS);
                    sumMax += max;
                    sumCommit += commit.isFound() ? commit.getOffset() : 0;
                } catch (Exception e) {
                    // A transient failure during overload must not kill the monitor; skip
                    // this sample and keep the previous baselines for the next diff.
                    roundOk = false;
                    break;
                }
            }
            if (!roundOk) {
                continue;
            }
            long now = System.currentTimeMillis();
            double dt = (now - prevMs) / 1000.0;
            if (primed && dt > 0) {
                System.out.printf("%-8.0f %12.0f %12.0f %14d%n", (now - runStart) / 1000.0,
                        (sumMax - prevMax) / dt, (sumCommit - prevCommit) / dt, sumMax - sumCommit);
            }
            prevMax = sumMax;
            prevCommit = sumCommit;
            prevMs = now;
            primed = true;
        }
        running = false;
        query.destroy();
        for (StoreConsumer consumer : consumers) {
            consumer.shutdown();
        }
        System.out.println("Consumer monitor complete.");
    }

    // ---------------------------------------------------------------- broker

    private static StoreBroker startBroker(Conf conf) throws IOException {
        StoreConfig config = new StoreConfig();
        config.setStorePath(conf.storePath);
        config.setBrokerName("bench-broker");
        config.setSyncFlush(false);                       // ASYNC_FLUSH per test goal
        config.setFileRetentionMillis(conf.retentionMillis);
        config.setCleanupIntervalMillis(conf.cleanupIntervalMillis);
        config.setDiskMaxUsedSpaceRatio(conf.diskRatio);
        config.setCleanupKeepFiles(conf.cleanupKeepFiles);
        StoreBroker broker = new StoreBroker(config, conf.port, conf.serialization);
        broker.start();
        return broker;
    }

    private static void printBanner(Conf conf, String directUrl) {
        int encodedSize = MessageCodec.calTotalSize(conf.msgSize, TOPIC.length())
                + ConsumeQueue.CQ_STORE_UNIT_SIZE;
        long peakRate = 0;
        for (int s : conf.steps) {
            peakRate = Math.max(peakRate, s);
        }
        printDiskBudget(conf, encodedSize, peakRate);
        System.out.printf("broker=%s msgSize=%d on-disk≈%d B/msg | producers=%d consumer=1 (pumps=queues=%d) sendMode=%s maxInflight=%d%n",
                directUrl, conf.msgSize, encodedSize, conf.producers, conf.queues, conf.sendMode, conf.maxInflight);
        System.out.printf("pullBatch=%d pullThreshold=%d suspendTimeout=%dms retention=%dms cleanup=%dms disk %d%%/stop %d%% keep=%d%n",
                conf.pullBatch, conf.pullThreshold, conf.suspendTimeout, conf.retentionMillis,
                conf.cleanupIntervalMillis, conf.diskRatio, conf.diskStopRatio, conf.cleanupKeepFiles);
        System.out.printf("steps=%s (0 = free-run)%n", java.util.Arrays.toString(conf.steps));
    }

    /** Resident-bytes forecast: the only thing that fills the disk is rate × window. */
    private static void printDiskBudget(Conf conf, int bytesPerMsg, long peakRate) {
        File dir = new File(conf.storePath);
        long total = dir.getTotalSpace();
        long usable = dir.getUsableSpace();
        double usedNow = 100.0 * (total - usable) / total;
        // window = TTL + one cleanup interval + up to one full file of roll granularity
        double windowSec = conf.retentionMillis / 1000.0 + conf.cleanupIntervalMillis / 1000.0
                + 1.0 * 1024 * 1024 * 1024 / Math.max(1.0, (double) peakRate * bytesPerMsg);
        long resident = (long) (peakRate * bytesPerMsg * windowSec);
        long headroomToStop = (long) ((conf.diskStopRatio - usedNow) / 100.0 * total);
        System.out.printf("volume %.1f%% used, forecast peak resident ≈ %.1f GB (window %.0fs), headroom to %d%% ≈ %.1f GB -> %s%n",
                usedNow, resident / 1e9, windowSec, conf.diskStopRatio, headroomToStop / 1e9,
                resident < headroomToStop ? "SAFE" : "EXCEEDS HEADROOM — lower rate/retention or add disk");
    }

    // ---------------------------------------------------------------- clients

    /**
     * Start one {@link StoreProducer} per producer thread — each owns a dedicated broker
     * connection, so the write side fans out across EventLoops instead of multiplexing
     * every send onto one. Producers are gated on {@code currentStep} (still -1), so
     * they idle until the first stage.
     */
    private List<StoreProducer> startProducers() {
        List<StoreProducer> producers = new ArrayList<>();
        for (int p = 0; p < conf.producers; p++) {
            StoreProducer producer = new StoreProducer(directUrl, conf.serialization,
                    conf.requestTimeout, conf.maxInflight);
            final int id = p;
            Thread t = new Thread(() -> producerLoop(producer, id), "pipeline-producer-" + id);
            t.setDaemon(true);
            t.start();
            producers.add(producer);
        }
        return producers;
    }

    /**
     * Start ONE {@link StoreConsumer} (one group) and subscribe every queue. Exclusive
     * permits a single reader per queue, so a second same-group instance would fail its
     * lease claim; read parallelism instead comes from the queue count — the consumer runs
     * one pump per subscription, giving queues concurrent in-flight pulls multiplexed on its
     * single broker connection (the pump/consume thread pools inside StoreConsumer are
     * fixed). The listener only counts messages; the keep-up rate is measured from the
     * broker's committed-offset frontier.
     */
    private List<StoreConsumer> startConsumers() {
        StoreConsumer consumer = new StoreConsumer(directUrl, conf.group, conf.serialization);
        consumer.setPullBatchSize(conf.pullBatch);
        consumer.setPullThresholdForQueue(conf.pullThreshold);
        consumer.setSuspendTimeoutMillis(conf.suspendTimeout);
        for (int q = 0; q < conf.queues; q++) {
            consumer.subscribe(TOPIC, q, messages -> consumed.addAndGet(messages.size()));
        }
        consumer.setConsumerId("bench-consumer");
        consumer.start();
        return List.of(consumer);
    }

    /** A direct StoreService reference used only to read broker-side offsets in the consumer role. */
    private ReferenceConfig<StoreService> openQueryReference() {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(JawsConstants.PROTOCOL_JAWS);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory("netty");
        protocolConfig.setSerialization(conf.serialization);

        ReferenceConfig<StoreService> reference = new ReferenceConfig<>();
        reference.setInterface(StoreService.class);
        reference.setApplication("jaws-store-bench-query");
        reference.setModule("store");
        reference.setGroup(StoreBroker.GROUP);
        reference.setVersion(StoreBroker.VERSION);
        reference.setRequestTimeout(conf.requestTimeout);
        reference.setProtocol(protocolConfig);
        reference.setDirectUrl(directUrl);
        return reference;
    }

    /**
     * Sampler thread. In the in-process {@code all} role it also records the
     * accepted-minus-consumed backlog per stage; in producer-only mode it drives just
     * the disk guard (and only when the volume is locally visible). It never dies on a
     * transient filesystem read hiccup, matching the kernel bench's belt-and-braces.
     */
    private Thread newSamplerThread(boolean measureBacklog) {
        Thread t = new Thread(() -> {
            File volumeDir = new File(conf.storePath);
            while (running) {
                LockSupport.parkNanos(1_000_000_000L);
                long backlog = measureBacklog ? Math.max(0, accepted.get() - consumed.get()) : 0;
                long total = volumeDir.getTotalSpace();
                long usable = volumeDir.getUsableSpace();
                double ratio = total > 0 ? 100.0 * (total - usable) / total : -1;
                if (ratio >= conf.diskStopRatio && !stopProducing) {
                    stopProducing = true;
                    System.out.printf("!! disk ratio %.1f%% >= stop ratio %d%%, refusing further produces%n",
                            ratio, conf.diskStopRatio);
                }
                if (measureBacklog) {
                    snapshots.add(new Snapshot(System.currentTimeMillis(), accepted.get(), consumed.get(),
                            backlog, ratio, (int) currentStep.get()));
                }
            }
        }, "pipeline-sampler");
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ---------------------------------------------------------------- stages

    /** Run the open-loop staircase, switching {@code currentStep} per stage. */
    private List<String> runStages(List<StoreProducer> producers) throws InterruptedException {
        List<String> report = new ArrayList<>();
        boolean inProcess = "all".equals(conf.role);
        for (int i = 0; i < conf.steps.length && i < MAX_STEPS; i++) {
            int target = conf.steps[i];
            boolean unlimited = target <= 0;
            int seconds = unlimited ? conf.finalStepSeconds : conf.stepSeconds;
            currentStep.set(i);
            long stepStart = System.nanoTime();
            long acceptedBefore = accepted.get();
            long offeredBefore = offered.get();
            long failedBefore = failedSend.get();
            Thread.sleep(seconds * 1000L);
            long elapsedMs = (System.nanoTime() - stepStart) / 1_000_000;
            report.add(inProcess
                    ? summarizeInProcessStep(i, target, unlimited, elapsedMs, acceptedBefore, offeredBefore, failedBefore)
                    : summarizeProducerStep(i, target, unlimited, elapsedMs, acceptedBefore, offeredBefore, failedBefore));
            if (stopProducing) {
                System.out.println("!! producers hard-stopped by diskStopRatio, aborting remaining steps");
                break;
            }
        }
        return report;
    }

    /**
     * Open-loop paced producer; target==0 stages free-run. Sends are issued without
     * waiting on the completion (async/oneway), so a slow consumer never throttles the
     * producer — the StoreProducer inflight semaphore is the only client-side gate, and
     * when the broker can't absorb the offered rate it surfaces as backlog/failures in
     * the report, exactly what this run is meant to observe.
     */
    private void producerLoop(StoreProducer producer, int producerId) {
        byte[] body = new byte[conf.msgSize];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) ('a' + i % 26);
        }
        int q = producerId % conf.queues;
        boolean oneway = "oneway".equals(conf.sendMode);
        long deadline = System.nanoTime();
        while (running && !stopProducing) {
            int stepIdx = (int) currentStep.get();
            if (stepIdx < 0) {
                LockSupport.parkNanos(1_000_000L);
                continue;
            }
            int target = conf.steps[stepIdx];
            if (target > 0) {
                long perThread = Math.max(1, target / conf.producers);
                long intervalNs = 1_000_000_000L / perThread;
                long now = System.nanoTime();
                if (now < deadline) {
                    LockSupport.parkNanos(Math.min(intervalNs, deadline - now));
                } else if (now - deadline > 4 * intervalNs) {
                    deadline = now;   // give back accumulated debt instead of bursting after a stall
                }
                deadline += intervalNs;
            }
            q = (q + 1) % conf.queues;
            // a fresh StoreMessage per send sharing the immutable body: with many sends
            // in flight the object may be serialized after this thread moves on, so it
            // must not be mutated and reused the way the join()-based kernel bench could.
            StoreMessage msg = new StoreMessage(TOPIC, q, body);
            offered.incrementAndGet();
            long t0 = System.nanoTime();
            try {
                if (oneway) {
                    producer.sendOneway(msg);
                    // no completion signal by definition: count it as accepted at offer so
                    // the backlog figure stays honest, and skip per-send latency.
                    accepted.incrementAndGet();
                } else {
                    final int step = stepIdx;
                    CompletableFuture<PutResult> future = producer.sendAsync(msg);
                    future.whenComplete((result, ex) -> recordSend(step, System.nanoTime() - t0, result, ex));
                }
            } catch (Throwable t) {
                // A free-run burst can outrun the broker's bounded pool and mark the
                // client unavailable, which surfaces as a synchronous throw here. Die on
                // it and the producer threads vanish the moment the interesting stage
                // starts; count it as a failed send instead so the thread lives and the
                // report reflects the saturation.
                failedSend.incrementAndGet();
            }
        }
    }

    private void recordSend(int stepIdx, long latNs, PutResult result, Throwable ex) {
        latSumNs.addAndGet(stepIdx, latNs);
        latCount.incrementAndGet(stepIdx);
        accumulateMax(latMaxNs, stepIdx, latNs);
        if (latNs > STALL_NANOS) {
            stallCount.incrementAndGet(stepIdx);
        }
        if (ex != null || result == null || !result.isOk()) {
            failedSend.incrementAndGet();
        } else {
            accepted.incrementAndGet();
        }
    }

    private static void accumulateMax(AtomicLongArray arr, int idx, long value) {
        long prev;
        do {
            prev = arr.get(idx);
            if (prev >= value) {
                return;
            }
        } while (!arr.compareAndSet(idx, prev, value));
    }

    // ----------------------------------------------------------------- report

    /** In-process role: full line including consumed rate and backlog (accepted - consumed). */
    private String summarizeInProcessStep(int stepIdx, int target, boolean unlimited, long elapsedMs,
                                          long acceptedBefore, long offeredBefore, long failedBefore) {
        long now = System.currentTimeMillis();
        List<Snapshot> window = windowFor(stepIdx, elapsedMs, now);
        double secs = elapsedMs / 1000.0;
        double acceptedRate = (accepted.get() - acceptedBefore) / secs;
        double offeredRate = (offered.get() - offeredBefore) / secs;

        long backlogStart = window.isEmpty() ? 0 : window.get(0).backlog;
        long backlogEnd = window.isEmpty() ? Math.max(0, accepted.get() - consumed.get())
                : window.get(window.size() - 1).backlog;
        double consumedRate = window.size() >= 2
                ? (window.get(window.size() - 1).consumed - window.get(0).consumed)
                    / ((window.get(window.size() - 1).atMillis - window.get(0).atMillis) / 1000.0)
                : 0;
        double backlogSlope = (backlogEnd - backlogStart) / Math.max(1, window.size() - 1);
        double diskEnd = window.isEmpty() ? -1 : window.get(window.size() - 1).diskRatio;

        String verdict = verdict(offeredRate, target, unlimited, backlogSlope, backlogStart, backlogEnd, failedBefore);
        return String.format(
                "step %d target=%-8s | offered %8.0f accepted %8.0f/s | consumed %8.0f/s | backlog %d->%d (%+.0f/s) | failed=%d | disk %.1f%% | %s | send avg %.0fus max %.1fms stalls=%d",
                stepIdx, unlimited ? "MAX" : String.valueOf(target),
                offeredRate, acceptedRate, consumedRate,
                backlogStart, backlogEnd, backlogSlope, failedSend.get() - failedBefore, diskEnd, verdict,
                avgUs(stepIdx), latMaxNs.get(stepIdx) / 1e6, stallCount.get(stepIdx));
    }

    /** Producer-only role: production-side line; keep-up verdict lives in the consumer process. */
    private String summarizeProducerStep(int stepIdx, int target, boolean unlimited, long elapsedMs,
                                         long acceptedBefore, long offeredBefore, long failedBefore) {
        double secs = elapsedMs / 1000.0;
        double acceptedRate = (accepted.get() - acceptedBefore) / secs;
        double offeredRate = (offered.get() - offeredBefore) / secs;
        long failed = failedSend.get() - failedBefore;
        String verdict = failed > 0 ? "SEND_FAILED"
                : (!unlimited && offeredRate < target * 0.9) ? "PACER_SHORTFALL" : "OFFER_OK";
        return String.format(
                "step %d target=%-8s | offered %8.0f accepted %8.0f/s | failed=%d | send avg %.0fus max %.1fms stalls=%d | %s",
                stepIdx, unlimited ? "MAX" : String.valueOf(target),
                offeredRate, acceptedRate, failed,
                avgUs(stepIdx), latMaxNs.get(stepIdx) / 1e6, stallCount.get(stepIdx), verdict);
    }

    private List<Snapshot> windowFor(int stepIdx, long elapsedMs, long now) {
        List<Snapshot> window = new ArrayList<>();
        for (Snapshot s : snapshots) {
            if (s.stepIndex == stepIdx && now - s.atMillis <= (elapsedMs / 1000 + 2) * 1000L) {
                window.add(s);
            }
        }
        window.sort(Comparator.comparingLong(s -> s.atMillis));
        return window;
    }

    private double avgUs(int stepIdx) {
        long count = latCount.get(stepIdx);
        return count == 0 ? 0 : latSumNs.get(stepIdx) / (double) count / 1000.0;
    }

    private String verdict(double offeredRate, int target, boolean unlimited,
                           double backlogSlope, long backlogStart, long backlogEnd, long failedBefore) {
        long failed = failedSend.get() - failedBefore;
        if (failed > 0) {
            return "SEND_FAILED";
        } else if (backlogSlope > Math.max(2000, (backlogStart + backlogEnd) / 20.0)) {
            return "BACKLOG_GROWS";
        } else if (!unlimited && offeredRate < target * 0.9) {
            // paced stage only: the pacer itself failed to offer the target rate;
            // a free-run stage has no target, its capacity is read from offered
            return "PACER_SHORTFALL";
        } else {
            return "KEEP_UP_OK";
        }
    }

    // ------------------------------------------------------------------- misc

    private static void wipeDir(String path) throws IOException {
        File dir = new File(path);
        if (!dir.exists()) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException("Failed to wipe " + p, e);
                }
            });
        }
        Files.createDirectories(Paths.get(path));
    }
}
