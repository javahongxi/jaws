package org.hongxi.jaws.sample.store;

import org.hongxi.jaws.store.ConsumeQueue;
import org.hongxi.jaws.store.MessageCodec;
import org.hongxi.jaws.store.MessageStore;
import org.hongxi.jaws.store.PullResult;
import org.hongxi.jaws.store.PutResult;
import org.hongxi.jaws.store.StoreConfig;
import org.hongxi.jaws.store.StoreMessage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

/**
 * Extreme-throughput benchmark for the raw storage kernel: producers drive
 * {@link MessageStore#putMessageAsync} and pumps drive {@link MessageStore#getMessage}
 * directly in one JVM, deliberately bypassing the RPC layer so the measured
 * ceiling is the engine (global put lock + mmap + page cache + cleanup), not
 * the single-connection client EventLoop.
 *
 * <p>Method: an OPEN-loop staged ramp — each stage offers a fixed target rate
 * (msg/s) regardless of observed latency, so "the store is slow" shows up as
 * backlog growth and reject codes, never as the load generator throttling
 * itself. The last stage where consumption keeps up (backlog flat), nothing is
 * rejected, and the disk stays flat is the "consumers can just keep up" limit;
 * a final target=0 stage free-runs to measure raw write capacity.
 *
 * <p>Disk safety: resident bytes ≈ write rate × (fileRetentionMillis +
 * cleanupIntervalMillis + one file of roll granularity), so the run pins a
 * short retention + fast cleanup sweep, and the sampler hard-stops producers
 * at {@code -DdiskStopRatio} — the belt-and-braces counterpart of RocketMQ's
 * 90% disk-full write refusal.
 *
 * <p>Not a unit test: no JUnit annotations, excluded from CI; run it as a main.
 * Lives in the store sample module next to the broker/producer/consumer demo
 * mains, so it runs through the same run-sample.sh classpath conventions as
 * the other benchmarks (the semantic regression suite for this load shape
 * stays in the jaws-store test source set).
 *
 * <pre>
 * Usage (or simply: ./run-sample.sh bench-store):
 *   java -cp ... org.hongxi.jaws.sample.store.StoreBenchmark \
 *     [-DstorePath=/tmp/jaws-store-bench] [-DmsgSize=128] [-Dqueues=8] \
 *     [-Dproducers=16] [-DpullBatch=256] \
 *     [-Dsteps=50000,100000,200000,400000,0] [-DstepSeconds=30] [-DfinalStepSeconds=60] \
 *     [-DretentionMillis=60000] [-DcleanupIntervalMillis=3000] \
 *     [-DdiskRatio=75] [-DdiskStopRatio=85] [-DcleanupKeepFiles=2]
 * </pre>
 */
public final class StoreBenchmark {

    private static final String TOPIC = "bench-topic";
    private static final int MAX_STEPS = 16;
    /** Puts slower than this are counted as stalls (file-roll warm-up under
     * the global lock is the expected source). */
    private static final long STALL_NANOS = 10_000_000L;

    private static final class Conf {
        final String storePath = System.getProperty("storePath", System.getProperty("user.home") + "/jaws-store-bench");
        final int msgSize = Integer.getInteger("msgSize", 128);
        final int queues = Integer.getInteger("queues", 8);
        final int producers = Integer.getInteger("producers", 16);
        final int pumpsPerQueue = Integer.getInteger("pumpsPerQueue", 1);
        final int pullBatch = Integer.getInteger("pullBatch", 256);
        final int stepSeconds = Integer.getInteger("stepSeconds", 30);
        final int finalStepSeconds = Integer.getInteger("finalStepSeconds", 60);
        final long retentionMillis = Long.getLong("retentionMillis", 60_000L);
        final int cleanupIntervalMillis = Integer.getInteger("cleanupIntervalMillis", 3_000);
        final int diskRatio = Integer.getInteger("diskRatio", 75);
        final int diskStopRatio = Integer.getInteger("diskStopRatio", 85);
        final int cleanupKeepFiles = Integer.getInteger("cleanupKeepFiles", 2);
        final int[] steps = parseSteps(System.getProperty("steps", "50000,100000,200000,400000,0"));

        static int[] parseSteps(String raw) {
            String[] parts = raw.split(",");
            int[] out = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                out[i] = Integer.parseInt(parts[i].trim());
            }
            return out;
        }
    }

    /** One sampler line; step boundaries are located by timestamp + stepIndex. */
    private static final class Snapshot {
        final long atMillis;
        final long accepted, consumed, lost;
        final long backlog;
        final double diskRatio;
        final long commitlogBytes;
        final int commitlogFiles;
        final int stepIndex;

        Snapshot(long atMillis, long accepted, long consumed, long lost, long backlog,
                 double diskRatio, long commitlogBytes, int commitlogFiles, int stepIndex) {
            this.atMillis = atMillis;
            this.accepted = accepted;
            this.consumed = consumed;
            this.lost = lost;
            this.backlog = backlog;
            this.diskRatio = diskRatio;
            this.commitlogBytes = commitlogBytes;
            this.commitlogFiles = commitlogFiles;
            this.stepIndex = stepIndex;
        }
    }

    private final Conf conf;
    private final MessageStore store;

    // producer-side counters (cumulative; per-step values are windowed diffs)
    private final AtomicLong offered = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejectedOsBusy = new AtomicLong();
    private final AtomicLong rejectedNotAvailable = new AtomicLong();
    private final AtomicLong rejectedOther = new AtomicLong();
    private final AtomicLongArray latSumNs = new AtomicLongArray(MAX_STEPS);
    private final AtomicLongArray latCount = new AtomicLongArray(MAX_STEPS);
    private final AtomicLongArray latMaxNs = new AtomicLongArray(MAX_STEPS);
    private final AtomicLongArray stallCount = new AtomicLongArray(MAX_STEPS);

    // consumer-side state, one slot per queue
    private final AtomicLongArray offsetPerQueue;
    private final AtomicLong consumed = new AtomicLong();
    private final AtomicLong lostToCleanup = new AtomicLong();
    private final AtomicLong illegalClamps = new AtomicLong();
    private final AtomicLong emptyPulls = new AtomicLong();

    private final ConcurrentLinkedQueue<Snapshot> snapshots = new ConcurrentLinkedQueue<>();
    private final AtomicLong currentStep = new AtomicLong(-1);
    private volatile boolean stopProducing;
    private volatile boolean running = true;

    private StoreBenchmark(Conf conf, MessageStore store) {
        this.conf = conf;
        this.store = store;
        this.offsetPerQueue = new AtomicLongArray(conf.queues);
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        Conf conf = new Conf();
        wipeDir(conf.storePath);

        StoreConfig config = new StoreConfig();
        config.setStorePath(conf.storePath);
        config.setSyncFlush(false);                       // ASYNC_FLUSH per test goal
        config.setFileRetentionMillis(conf.retentionMillis);
        config.setCleanupIntervalMillis(conf.cleanupIntervalMillis);
        config.setDiskMaxUsedSpaceRatio(conf.diskRatio);
        config.setCleanupKeepFiles(conf.cleanupKeepFiles);
        MessageStore store = new MessageStore(config);
        store.start();

        int encodedSize = MessageCodec.calTotalSize(conf.msgSize, TOPIC.length());
        int cqUnit = ConsumeQueue.CQ_STORE_UNIT_SIZE;
        long peakRate = 0;
        for (int s : conf.steps) {
            peakRate = Math.max(peakRate, s);
        }
        System.out.println("=== jaws-store kernel benchmark ===");
        System.out.printf("storePath=%s msgSize=%d encoded=%d+%d=CQ -> %d B/msg on disk%n",
                conf.storePath, conf.msgSize, encodedSize, cqUnit, encodedSize + cqUnit);
        System.out.printf("retention=%dms cleanupInterval=%dms diskRatio=%d%% stopRatio=%d%% keepFiles=%d%n",
                conf.retentionMillis, conf.cleanupIntervalMillis, conf.diskRatio, conf.diskStopRatio, conf.cleanupKeepFiles);
        printDiskBudget(conf, encodedSize + cqUnit, peakRate);

        StoreBenchmark bench = new StoreBenchmark(conf, store);
        Thread sampler = bench.newSamplerThread();
        List<Thread> workers = bench.startWorkers();
        Thread.sleep(1000);

        List<String> report = new ArrayList<>();
        for (int i = 0; i < conf.steps.length && i < MAX_STEPS; i++) {
            int target = conf.steps[i];
            boolean unlimited = target <= 0;
            int seconds = unlimited ? conf.finalStepSeconds : conf.stepSeconds;
            bench.currentStep.set(i);
            long stepStart = System.nanoTime();
            long acceptedBefore = bench.accepted.get();
            long offeredBefore = bench.offered.get();
            long rejectedBefore = bench.rejectedOsBusy.get() + bench.rejectedNotAvailable.get() + bench.rejectedOther.get();
            Thread.sleep(seconds * 1000L);
            long elapsedMs = (System.nanoTime() - stepStart) / 1_000_000;
            report.add(bench.summarizeStep(i, target, unlimited, elapsedMs,
                    acceptedBefore, offeredBefore, rejectedBefore));
            if (bench.stopProducing) {
                System.out.println("!! producers hard-stopped by diskStopRatio, aborting remaining steps");
                break;
            }
        }

        bench.running = false;
        for (Thread t : workers) {
            t.join(5000);
        }
        sampler.join(5000);
        for (String line : report) {
            System.out.println(line);
        }
        store.shutdown();
        System.out.println("Done. Data left under " + conf.storePath);
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

    // ---------------------------------------------------------------- workers

    private List<Thread> startWorkers() {
        List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < conf.producers; p++) {
            Thread t = new Thread(this::producerLoop, "bench-producer-" + p);
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }
        for (int q = 0; q < conf.queues; q++) {
            final int queueId = q;
            for (int k = 0; k < conf.pumpsPerQueue; k++) {
                Thread t = new Thread(() -> pumpLoop(queueId), "bench-pump-" + queueId + "-" + k);
                t.setDaemon(true);
                t.start();
                threads.add(t);
            }
        }
        return threads;
    }

    private Thread newSamplerThread() {
        Thread t = new Thread(() -> {
            File commitlogDir = new File(conf.storePath + File.separator + "commitlog");
            File volumeDir = new File(conf.storePath);
            while (running) {
                LockSupport.parkNanos(1_000_000_000L);
                long backlog = 0;
                for (int q = 0; q < conf.queues; q++) {
                    backlog += Math.max(0, store.getMaxOffsetInQueue(TOPIC, q) - offsetPerQueue.get(q));
                }
                File[] files = commitlogDir.listFiles();
                long clBytes = 0;
                int clFiles = 0;
                if (files != null) {
                    for (File f : files) {
                        clBytes += f.length();
                        clFiles++;
                    }
                }
                long total = volumeDir.getTotalSpace();
                long usable = volumeDir.getUsableSpace();
                double ratio = total > 0 ? 100.0 * (total - usable) / total : -1;
                if (ratio >= conf.diskStopRatio && !stopProducing) {
                    stopProducing = true;
                    System.out.printf("!! disk ratio %.1f%% >= stop ratio %d%%, refusing further produces%n",
                            ratio, conf.diskStopRatio);
                }
                snapshots.add(new Snapshot(System.currentTimeMillis(), accepted.get(), consumed.get(),
                        lostToCleanup.get(), backlog, ratio, clBytes, clFiles, (int) currentStep.get()));
            }
        }, "bench-sampler");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Open-loop paced producer; target==0 stages free-run. */
    private void producerLoop() {
        byte[] body = new byte[conf.msgSize];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) ('a' + i % 26);
        }
        StoreMessage msg = new StoreMessage();
        msg.setTopic(TOPIC);
        msg.setBody(body);
        int q = (int) (Thread.currentThread().getId() % conf.queues);
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
            msg.setQueueId(q = (q + 1) % conf.queues);
            offered.incrementAndGet();
            long t0 = System.nanoTime();
            PutResult result = store.putMessageAsync(msg).join();
            recordPut(stepIdx, System.nanoTime() - t0, result);
        }
    }

    private void recordPut(int stepIdx, long latNs, PutResult result) {
        latSumNs.addAndGet(stepIdx, latNs);
        latCount.incrementAndGet(stepIdx);
        accumulateMax(latMaxNs, stepIdx, latNs);
        if (latNs > STALL_NANOS) {
            stallCount.incrementAndGet(stepIdx);
        }
        switch (result.getStatus()) {
            case PUT_OK -> accepted.incrementAndGet();
            case OS_PAGE_CACHE_BUSY -> rejectedOsBusy.incrementAndGet();
            case SERVICE_NOT_AVAILABLE -> rejectedNotAvailable.incrementAndGet();
            default -> rejectedOther.incrementAndGet();
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

    /** Short-polling pump for one queue; offsets live only in this array. */
    private void pumpLoop(int queueId) {
        long offset = offsetPerQueue.get(queueId);
        while (running) {
            try {
                offset = pumpOnce(queueId, offset);
            } catch (Throwable t) {
                // A pump must never die mid-run: surface the failure in the log,
                // back off, and resume from the same offset so the window shows it.
                System.out.printf("!! pump-%d failed at offset %d: %s%n", queueId, offset, t);
                LockSupport.parkNanos(50_000_000L);
            }
        }
    }

    private long pumpOnce(int queueId, long offset) {
        PullResult result = store.getMessage(TOPIC, queueId, offset, conf.pullBatch);
        switch (result.getStatus()) {
            case FOUND -> {
                int n = result.getMessages().size();
                long prev = offset;
                offset = result.getNextBeginOffset();
                offsetPerQueue.set(queueId, offset);
                consumed.addAndGet(offset - prev);
                if (n < conf.pullBatch) {
                    LockSupport.parkNanos(200_000);   // drained: tiny breather, keeps pumps hot
                }
            }
            case NO_NEW_MSG -> {
                emptyPulls.incrementAndGet();
                offset = result.getNextBeginOffset();
                offsetPerQueue.set(queueId, offset);
                LockSupport.parkNanos(1_000_000L);
            }
            case OFFSET_ILLEGAL -> {
                // cleanup reclaimed past this consumer: count the silent loss, then clamp
                long next = result.getNextBeginOffset();
                if (next > offset) {
                    lostToCleanup.addAndGet(next - offset);
                }
                illegalClamps.incrementAndGet();
                offset = next;
                offsetPerQueue.set(queueId, offset);
            }
        }
        return offset;
    }

    // ----------------------------------------------------------------- report

    private String summarizeStep(int stepIdx, int target, boolean unlimited, long elapsedMs,
                                 long acceptedBefore, long offeredBefore, long rejectedBefore) {
        long now = System.currentTimeMillis();
        List<Snapshot> window = new ArrayList<>();
        for (Snapshot s : snapshots) {
            if (s.stepIndex == stepIdx && now - s.atMillis <= (elapsedMs / 1000 + 2) * 1000L) {
                window.add(s);
            }
        }
        window.sort(Comparator.comparingLong(s -> s.atMillis));
        double secs = elapsedMs / 1000.0;
        double acceptedRate = (accepted.get() - acceptedBefore) / secs;
        double offeredRate = (offered.get() - offeredBefore) / secs;
        long count = latCount.get(stepIdx);
        double avgUs = count == 0 ? 0 : latSumNs.get(stepIdx) / (double) count / 1000.0;
        double maxMs = latMaxNs.get(stepIdx) / 1e6;

        long backlogStart = window.isEmpty() ? 0 : window.get(0).backlog;
        long backlogEnd = window.isEmpty() ? storeCommittedFrontier() : window.get(window.size() - 1).backlog;
        double consumedRate = window.size() >= 2
                ? (window.get(window.size() - 1).consumed - window.get(0).consumed) / ((window.get(window.size() - 1).atMillis - window.get(0).atMillis) / 1000.0)
                : 0;
        double backlogSlope = (backlogEnd - backlogStart) / Math.max(1, window.size() - 1);
        double diskEnd = window.isEmpty() ? -1 : window.get(window.size() - 1).diskRatio;
        long clBytesEnd = window.isEmpty() ? 0 : window.get(window.size() - 1).commitlogBytes;
        int clFilesEnd = window.isEmpty() ? 0 : window.get(window.size() - 1).commitlogFiles;
        long lostStep = window.isEmpty() ? 0 : window.get(window.size() - 1).lost - window.get(0).lost;

        String verdict;
        long rejected = (rejectedOsBusy.get() + rejectedNotAvailable.get() + rejectedOther.get()) - rejectedBefore;
        if (rejected > 0) {
            verdict = "REJECTED";
        } else if (backlogSlope > Math.max(2000, (backlogStart + backlogEnd) / 20.0)) {
            verdict = "BACKLOG_GROWS";
        } else if (!unlimited && offeredRate < target * 0.9) {
            // paced stage only: the pacer itself failed to offer the target rate;
            // a free-run stage has no target, its capacity is read from offered
            verdict = "PACER_SHORTFALL";
        } else {
            verdict = "KEEP_UP_OK";
        }
        return String.format(
                "step %d target=%-8s | offered %8.0f accepted %8.0f/s | consumed %8.0f/s | backlog %d->%d (%+.0f/s) | lost=%d rejected=%d | disk %.1f%% clFiles=%d (%.2f GB) | put avg %.0fus max %.1fms stalls=%d | %s",
                stepIdx, unlimited ? "MAX" : String.valueOf(target),
                offeredRate, acceptedRate, consumedRate,
                backlogStart, backlogEnd, backlogSlope, lostStep, rejected,
                diskEnd, clFilesEnd, clBytesEnd / 1e9, avgUs, maxMs, stallCount.get(stepIdx), verdict);
    }

    private long storeCommittedFrontier() {
        long total = 0;
        for (int q = 0; q < conf.queues; q++) {
            total += store.getMaxOffsetInQueue(TOPIC, q);
        }
        return total;
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
