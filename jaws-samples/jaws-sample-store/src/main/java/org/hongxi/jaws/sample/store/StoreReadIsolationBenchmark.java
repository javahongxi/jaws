package org.hongxi.jaws.sample.store;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.store.LeaseResult;
import org.hongxi.jaws.store.PullResult;
import org.hongxi.jaws.store.PullStatus;
import org.hongxi.jaws.store.PutResult;
import org.hongxi.jaws.store.StoreBroker;
import org.hongxi.jaws.store.StoreConfig;
import org.hongxi.jaws.store.StoreMessage;
import org.hongxi.jaws.store.StoreService;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Read-latency-under-write-load benchmark: the question it answers is
 * <em>does a fixed read workload slow down as concurrent write QPS is layered
 * on top</em>, i.e. whether the store's write path is already isolated from the
 * read path well enough that no further thread-pool partitioning is needed.
 *
 * <p>Unlike {@link StorePipelineBenchmark} (which measures <em>write</em> send
 * latency and consumer keep-up), this harness pins the <b>read</b> side: a fixed
 * fleet of pull RPCs per second, each latency timed, bucketed per phase. A
 * separate writer fleet ramps through a staircase of target write rates
 * (phase 0 is zero-write baseline). Comparing the read p50/p99/p999 across
 * phases isolates write interference: the read load never changes, so any
 * latency drift is attributable to the broker absorbing the concurrent writes.
 *
 * <p>Reads are served from a pre-seeded, fully-dispatched data set: the reader
 * cycles per-queue offsets inside {@code [0, seedPerQueue)} with
 * {@code suspendTimeoutMillis=0}, so every pull is a short-poll that walks the
 * ConsumeQueue index and slices the CommitLog — a real read, never a long-poll
 * hold whose latency would be dominated by waiting-for-arrival rather than the
 * read path itself. The retention window is set wider than the whole run so
 * cleanup never reclaims the seed out from under the reader.
 *
 * <p>Roles via {@code -Drole}:
 * <ul>
 *   <li>{@code all} (default) — in-process broker + this client. Easiest, but
 *       client and broker share CPU/GC, so absolute latencies read pessimistically;
 *       the <b>relative</b> baseline-vs-loaded comparison is what matters here.</li>
 *   <li>{@code broker} — start only a {@link StoreBroker} and block; run
 *       {@code client} in another process for a cleaner server-only contention picture.</li>
 *   <li>{@code client} — connect to {@code host:port}, seed, and drive the phases.</li>
 * </ul>
 *
 * <p>Read and write ride <b>separate</b> connections (distinct {@link ReferenceConfig}s),
 * so a write burst cannot head-of-line-block the reader on a shared TCP stream —
 * the interference we want to observe is server-side (dispatch pool, global write
 * lock, page cache), not client-side connection multiplexing.
 *
 * <p>Not a unit test: no JUnit annotations, excluded from CI; run it as a main.
 *
 * <pre>
 * Usage:
 *   java -cp ... org.hongxi.jaws.sample.store.StoreReadIsolationBenchmark \
 *     -Drole=all|broker|client [-Dhost=127.0.0.1] [-Dport=10911] \
 *     [-DstorePath=/tmp/jaws-store-iso] [-DmsgSize=128] [-Dqueues=8] \
 *     [-DseedPerQueue=20000] [-DreaderThreads=4] [-DreadRps=3000] [-DpullBatch=16] \
 *     [-DwriterConns=4] [-DwriteSteps=0,10000,30000,60000,100000] [-DphaseSeconds=20] \
 *     [-DretentionMillis=600000] [-DcleanupIntervalMillis=60000] [-DleaseMillis=600000]
 * </pre>
 */
public final class StoreReadIsolationBenchmark {

    private static final String TOPIC = "iso-topic";
    private static final String READER_GROUP = "iso-reader";
    private static final String READER_ID = "iso-reader-0";
    /** Pulls slower than this are counted as read stalls (ms). */
    private static final long STALL_NS = 10_000_000L;

    private static final class Conf {
        final String role = System.getProperty("role", "all");
        // In one JVM every reference to the same host:port multiplexes a single
        // client connection (AbstractTransportFactory keys the pool on hostPort),
        // so a "writer" burst would head-of-line-block the "reader" on the client
        // EventLoop and fake server-side interference. Split roles into separate
        // processes: mode=reader and mode=writer each get their own connection.
        // mode=all keeps them together for a quick smoke, confounded.
        final String mode = System.getProperty("mode", "all");
        // Absolute epoch-ms at which phase 0 opens; reader and writer processes are
        // given the same value so their phase windows line up. 0 = start on demand.
        final long startAt = Long.getLong("startAt", 0L);
        final String host = System.getProperty("host", "127.0.0.1");
        final int port = Integer.getInteger("port", 10911);
        final String storePath = System.getProperty("storePath", "/tmp/jaws-store-iso");
        final String serialization = System.getProperty("serialization", "fastjson2");
        final int requestTimeout = Integer.getInteger("requestTimeout", 30000);
        final int msgSize = Integer.getInteger("msgSize", 128);
        final int queues = Integer.getInteger("queues", 8);
        final int seedPerQueue = Integer.getInteger("seedPerQueue", 20000);
        final int readerThreads = Integer.getInteger("readerThreads", 4);
        final int readRps = Integer.getInteger("readRps", 3000);
        final int pullBatch = Integer.getInteger("pullBatch", 16);
        final int writerConns = Integer.getInteger("writerConns", 4);
        final int phaseSeconds = Integer.getInteger("phaseSeconds", 20);
        final long retentionMillis = Long.getLong("retentionMillis", 600_000L);
        final int cleanupIntervalMillis = Integer.getInteger("cleanupIntervalMillis", 60_000);
        final long leaseMillis = Long.getLong("leaseMillis", 600_000L);
        // Phase 0 is the zero-write baseline; the rest layer write QPS on top.
        final int[] writeSteps = parseSteps(System.getProperty("writeSteps", "0,10000,30000,60000,100000"));

        static int[] parseSteps(String raw) {
            String[] parts = raw.split(",");
            int[] out = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                out[i] = Integer.parseInt(parts[i].trim());
            }
            return out;
        }
    }

    /**
     * Latency buffer for one (thread, phase) slice. Adds happen inside pull
     * {@code whenComplete} callbacks, which run on Netty client EventLoop threads —
     * several in-flight pulls from the same reader thread can therefore complete
     * concurrently and mutate the same bag, so add/sortedCopy must be guarded.
     */
    private static final class LatencyBag {
        private long[] a = new long[2048];
        private int n;

        synchronized void add(long v) {
            if (n == a.length) {
                a = Arrays.copyOf(a, a.length * 2);
            }
            a[n++] = v;
        }

        synchronized long[] sortedCopy() {
            long[] c = Arrays.copyOf(a, n);
            Arrays.sort(c);
            return c;
        }
    }

    private static final class PhaseStat {
        long count;
        long p50;
        long p99;
        long p999;
        long max;
        long stalls;
        long notFound;
    }

    private final Conf conf;
    private final String directUrl;

    // bags[threadIdx][phaseIdx]; one writer (the reader thread) per [threadIdx] slice.
    private LatencyBag[][] readBags;
    private final AtomicLong[] notFoundByThread;
    private final AtomicLong[] stallsByThread;

    private final AtomicLong writeAccepted = new AtomicLong();
    private final AtomicLong writeFailed = new AtomicLong();
    private volatile int currentPhase;
    private volatile long startAtMillis;
    private volatile boolean running = true;
    private volatile boolean stopWriting;

    private StoreReadIsolationBenchmark(Conf conf) {
        this.conf = conf;
        this.directUrl = conf.host + ":" + conf.port;
        this.readBags = new LatencyBag[conf.readerThreads][conf.writeSteps.length];
        for (int t = 0; t < conf.readerThreads; t++) {
            for (int p = 0; p < conf.writeSteps.length; p++) {
                readBags[t][p] = new LatencyBag();
            }
        }
        this.notFoundByThread = new AtomicLong[conf.readerThreads];
        this.stallsByThread = new AtomicLong[conf.readerThreads];
        for (int t = 0; t < conf.readerThreads; t++) {
            notFoundByThread[t] = new AtomicLong();
            stallsByThread[t] = new AtomicLong();
        }
    }

    public static void main(String[] args) throws Exception {
        Conf conf = new Conf();
        switch (conf.role) {
            case "broker" -> runBrokerOnly(conf);
            default -> {
                // The in-process broker's Netty boss/worker loops are non-daemon (JVM keep-alive
                // by design), so an explicit exit is needed once the client finishes — and it must
                // run even if the run throws, otherwise the JVM hangs on a half-failed bench.
                try {
                    new StoreReadIsolationBenchmark(conf).runClient(conf.role.equals("all"));
                } finally {
                    System.exit(0);
                }
            }
        }
    }

    private static void runBrokerOnly(Conf conf) throws Exception {
        StoreBroker broker = startBroker(conf);
        System.out.println("StoreBroker (isolation bench) on " + conf.host + ":" + conf.port
                + ", storePath=" + conf.storePath + " — run ROLE=client against it. Ctrl+C to stop.");
        Runtime.getRuntime().addShutdownHook(new Thread(broker::shutdown, "StoreBrokerShutdown"));
        new CountDownLatch(1).await();
    }

    private static StoreBroker startBroker(Conf conf) throws Exception {
        wipeDir(conf.storePath);
        StoreConfig config = new StoreConfig();
        config.setStorePath(conf.storePath);
        config.setBrokerName("iso-broker");
        config.setSyncFlush(false);                     // ASYNC_FLUSH, per store bench convention
        config.setFileRetentionMillis(conf.retentionMillis);
        config.setCleanupIntervalMillis(conf.cleanupIntervalMillis);
        config.setDiskMaxUsedSpaceRatio(90);            // never let the watermark fuse fire mid-run
        config.setCleanupKeepFiles(2);
        StoreBroker broker = new StoreBroker(config, conf.port, conf.serialization);
        broker.start();
        return broker;
    }

    // ------------------------------------------------------------------ client

    private void runClient(boolean inProcess) throws Exception {
        StoreBroker broker = inProcess ? startBroker(conf) : null;
        boolean readerRole = conf.mode.equals("all") || conf.mode.equals("reader");
        boolean writerRole = conf.mode.equals("all") || conf.mode.equals("writer");
        System.out.println("=== jaws-store read-isolation benchmark [role=" + conf.role
                + " mode=" + conf.mode + "] ===");
        printBanner();

        List<ReferenceConfig<StoreService>> readerRefs = new ArrayList<>();
        List<ReferenceConfig<StoreService>> writerRefs = new ArrayList<>();
        if (readerRole) {
            for (int i = 0; i < conf.readerThreads; i++) {
                readerRefs.add(openRef("iso-reader-" + i));
            }
        }
        if (writerRole) {
            for (int i = 0; i < conf.writerConns; i++) {
                writerRefs.add(openRef("iso-writer-" + i));
            }
        }
        ReferenceConfig<StoreService> ctlRef = readerRole ? openRef("iso-ctl") : null;

        // The reader process owns setup: claim the exclusive lease for the reader
        // identity on every queue (so pulls are never fenced NOT_LEASED) and seed a
        // fully-dispatched data set. The writer process (separate JVM) skips this —
        // it only overlays writes against the already-seeded broker.
        if (readerRole) {
            StoreService ctl = ctlRef.getRef();
            for (int q = 0; q < conf.queues; q++) {
                LeaseResult lr = ctl.claimLeaseAsync(READER_GROUP, TOPIC, q, READER_ID, conf.leaseMillis)
                        .get(10, TimeUnit.SECONDS);
                if (!lr.isGranted()) {
                    throw new IllegalStateException("failed to claim lease on queue " + q + ": " + lr);
                }
            }
            seed(readerRefs.get(0).getRef());
            awaitIndexReady(ctl);
        }

        // Phase 0 opens at an absolute wall-clock instant shared by reader and writer
        // processes so their phase windows line up. Reader picks it (or honours a
        // supplied -DstartAt) and prints it; the operator feeds it to the writer.
        if (this.startAtMillis == 0L) {
            this.startAtMillis = conf.startAt > 0 ? conf.startAt : System.currentTimeMillis() + 3000L;
        }
        System.out.println("START_AT=" + startAtMillis + "  (pass -DstartAt=" + startAtMillis
                + " to the other process so phases align)");

        // Start readers (fixed rate) and writers (phase-paced); both idle until the
        // shared start instant, then take their target from the wall-clock phase.
        List<Thread> readerThreads = new ArrayList<>();
        for (int t = 0; t < readerRefs.size(); t++) {
            StoreService svc = readerRefs.get(t).getRef();
            final int tid = t;
            Thread th = new Thread(() -> readerLoop(svc, tid), "iso-reader-" + t);
            th.setDaemon(true);
            th.start();
            readerThreads.add(th);
        }
        List<Thread> writerThreads = new ArrayList<>();
        for (int w = 0; w < writerRefs.size(); w++) {
            StoreService svc = writerRefs.get(w).getRef();
            final int wid = w;
            Thread th = new Thread(() -> writerLoop(svc, wid), "iso-writer-" + w);
            th.setDaemon(true);
            th.start();
            writerThreads.add(th);
        }
        // Ticker advances currentPhase purely from wall clock, identical in both JVMs.
        Thread ticker = new Thread(this::phaseTicker, "iso-phase-ticker");
        ticker.setDaemon(true);
        ticker.start();

        if (readerRole) {
            runPhases();
        } else {
            // Writer-only process: overlay writes phase by phase (same wall-clock
            // schedule as the reader) and report the achieved write rate per phase so
            // the reader's latency table can be correlated with actual write load.
            System.out.printf("%n%-6s %10s %10s %10s %10s%n",
                    "phase", "writeTgt/s", "write/s", "accepted", "failed");
            long phaseMs2 = conf.phaseSeconds * 1000L;
            long acceptedPrev = 0, failedPrev = 0;
            for (int p = 0; p < conf.writeSteps.length; p++) {
                long phaseStartMs = startAtMillis + p * phaseMs2;
                long phaseEndMs = phaseStartMs + phaseMs2;
                long wait = phaseEndMs - System.currentTimeMillis();
                if (wait > 0) {
                    Thread.sleep(wait);
                }
                double dt = Math.max(0.001, phaseMs2 / 1000.0);
                long accNow = writeAccepted.get(), failNow = writeFailed.get();
                System.out.printf("%-6d %10d %10.0f %10d %10d%n",
                        p, conf.writeSteps[p], (accNow - acceptedPrev) / dt, accNow - acceptedPrev, failNow - failedPrev);
                acceptedPrev = accNow;
                failedPrev = failNow;
            }
            System.out.printf("%nwriter process done: accepted=%d failed=%d%n",
                    writeAccepted.get(), writeFailed.get());
        }

        running = false;
        joinQuietly(readerThreads);
        joinQuietly(writerThreads);
        readerRefs.forEach(ReferenceConfig::destroy);
        writerRefs.forEach(ReferenceConfig::destroy);
        if (ctlRef != null) {
            ctlRef.destroy();
        }
        if (broker != null) {
            broker.shutdown();
        }
        System.out.println("Done. Data left under " + conf.storePath);
    }

    /** currentPhase = which phase window "now" falls in, clamped to [0, steps-1]. */
    private int phaseOfNow() {
        long elapsed = System.currentTimeMillis() - startAtMillis;
        if (elapsed < 0) {
            return 0;
        }
        int p = (int) (elapsed / (conf.phaseSeconds * 1000L));
        return Math.min(p, conf.writeSteps.length - 1);
    }

    private void phaseTicker() {
        while (running) {
            currentPhase = phaseOfNow();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void printBanner() {
        File dir = new File(conf.storePath);
        long total = dir.getTotalSpace();
        long usable = dir.getUsableSpace();
        double usedNow = total > 0 ? 100.0 * (total - usable) / total : -1;
        long peakWrite = 0;
        for (int s : conf.writeSteps) {
            peakWrite = Math.max(peakWrite, s);
        }
        long seedMsgs = (long) conf.seedPerQueue * conf.queues;
        System.out.printf("broker=%s msgSize=%d | queues=%d seed/queue=%d (=%d msgs) | readerThreads=%d readRps=%d pullBatch=%d%n",
                directUrl, conf.msgSize, conf.queues, conf.seedPerQueue, seedMsgs,
                conf.readerThreads, conf.readRps, conf.pullBatch);
        System.out.printf("writerConns=%d phases=%s (0=no writes) phaseSeconds=%d | retention=%dms cleanup=%dms%n",
                conf.writerConns, Arrays.toString(conf.writeSteps), conf.phaseSeconds,
                conf.retentionMillis, conf.cleanupIntervalMillis);
        System.out.printf("volume %.1f%% used%n", usedNow);
    }

    /** Write seedPerQueue records per queue so the reader always has a dispatched range to walk. */
    private void seed(StoreService writer) throws Exception {
        System.out.printf("Seeding %d msgs/queue × %d queues ...%n", conf.seedPerQueue, conf.queues);
        byte[] body = new byte[conf.msgSize];
        ThreadLocalRandom.current().nextBytes(body);
        long t0 = System.currentTimeMillis();
        int batch = 500;
        List<CompletableFuture<List<PutResult>>> pending = new ArrayList<>();
        int perQueueBatch = conf.seedPerQueue / batch;
        for (int q = 0; q < conf.queues; q++) {
            for (int b = 0; b < perQueueBatch; b++) {
                List<StoreMessage> list = new ArrayList<>(batch);
                for (int i = 0; i < batch; i++) {
                    list.add(new StoreMessage(TOPIC, q, body));
                }
                pending.add(writer.putBatchAsync(list));
                if (pending.size() >= 200) {
                    drainBatches(pending);
                }
            }
        }
        drainBatches(pending);
        System.out.printf("Seeded %d msgs in %dms%n", (long) conf.seedPerQueue * conf.queues,
                System.currentTimeMillis() - t0);
    }

    private void drainBatches(List<CompletableFuture<List<PutResult>>> pending) throws Exception {
        for (CompletableFuture<List<PutResult>> f : pending) {
            f.get(30, TimeUnit.SECONDS);
        }
        pending.clear();
    }

    /** Wait until reput has dispatched every seeded record so the CQ index covers [0, seedPerQueue). */
    private void awaitIndexReady(StoreService ctl) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        for (int q = 0; q < conf.queues; q++) {
            while (System.currentTimeMillis() < deadline) {
                Long max = ctl.maxOffsetAsync(TOPIC, q).get(10, TimeUnit.SECONDS);
                if (max >= conf.seedPerQueue) {
                    break;
                }
                Thread.sleep(50);
            }
        }
        System.out.println("Index ready: all queues dispatched up to seed frontier.");
    }

    /**
     * Fixed-rate reader. Cycles per-queue offsets inside {@code [0, seedPerQueue)} with
     * suspend disabled, so every pull is a real short-poll read; latency per RPC is
     * bucketed into the current phase's bag.
     */
    private void readerLoop(StoreService svc, int tid) {
        long[] cursor = new long[conf.queues];
        for (int q = 0; q < conf.queues; q++) {
            cursor[q] = q % Math.max(1, conf.seedPerQueue);   // spread start points across the range
        }
        long perThreadRps = Math.max(1, conf.readRps / conf.readerThreads);
        long intervalNs = 1_000_000_000L / perThreadRps;
        long deadline = System.nanoTime();
        int q = tid % conf.queues;
        while (running) {
            int phase = currentPhase;
            // Reads flow before startAtMillis to warm the path, but only samples taken
            // once the phase schedule has begun are recorded (keeps baseline clean).
            boolean started = System.currentTimeMillis() >= startAtMillis;
            q = (q + 1) % conf.queues;
            long off = cursor[q];
            long finalOff = off;
            long t0 = System.nanoTime();
            try {
                svc.pullAsync(READER_GROUP, READER_ID, TOPIC, q, finalOff, conf.pullBatch, 0L)
                        .whenComplete((result, ex) -> {
                            if (!started) {
                                return;
                            }
                            long lat = System.nanoTime() - t0;
                            LatencyBag bag = readBags[tid][Math.min(phase, conf.writeSteps.length - 1)];
                            bag.add(lat);
                            if (lat > STALL_NS) {
                                stallsByThread[tid].incrementAndGet();
                            }
                            if (ex != null || result == null || result.getStatus() != PullStatus.FOUND) {
                                notFoundByThread[tid].incrementAndGet();
                            }
                        });
            } catch (Throwable t) {
                if (started) {
                    // A transient broker-busy/timeout still counts as a (failed) read sample.
                    readBags[tid][Math.min(phase, conf.writeSteps.length - 1)].add(System.nanoTime() - t0);
                    notFoundByThread[tid].incrementAndGet();
                }
            }
            // advance cursor, wrap inside the seeded range
            cursor[q] = (off + conf.pullBatch) % conf.seedPerQueue;

            long now = System.nanoTime();
            if (now < deadline) {
                LockSupport.parkNanos(Math.min(intervalNs, deadline - now));
            } else if (now - deadline > 4 * intervalNs) {
                deadline = now;
            }
            deadline += intervalNs;
        }
    }

    /**
     * Paced writer overlay. Its target rate follows {@code writeSteps[currentPhase]}
     * (0 → idle). Offers are async and uncounted on the read side; only the aggregate
     * accepted/failed totals matter for reporting the achieved write rate per phase.
     */
    private void writerLoop(StoreService svc, int wid) {
        byte[] body = new byte[conf.msgSize];
        ThreadLocalRandom.current().nextBytes(body);
        long deadline = System.nanoTime();
        int q = wid % conf.queues;
        while (running && !stopWriting) {
            if (System.currentTimeMillis() < startAtMillis) {
                LockSupport.parkNanos(2_000_000L);
                deadline = System.nanoTime();
                continue;
            }
            int phase = currentPhase;
            int target = conf.writeSteps[Math.min(phase, conf.writeSteps.length - 1)];
            if (target <= 0) {
                LockSupport.parkNanos(1_000_000L);
                deadline = System.nanoTime();
                continue;
            }
            long perThread = Math.max(1, target / conf.writerConns);
            long intervalNs = 1_000_000_000L / perThread;
            long now = System.nanoTime();
            if (now < deadline) {
                LockSupport.parkNanos(Math.min(intervalNs, deadline - now));
            } else if (now - deadline > 4 * intervalNs) {
                deadline = now;
            }
            deadline += intervalNs;

            q = (q + 1) % conf.queues;
            try {
                svc.putAsync(new StoreMessage(TOPIC, q, body)).whenComplete((r, ex) -> {
                    if (ex != null || r == null || !r.isOk()) {
                        writeFailed.incrementAndGet();
                    } else {
                        writeAccepted.incrementAndGet();
                    }
                });
            } catch (Throwable t) {
                writeFailed.incrementAndGet();
            }
        }
    }

    private void runPhases() throws Exception {
        System.out.printf("%n%-6s %10s %10s %10s %10s %10s %10s %10s %8s %8s%n",
                "phase", "writeTgt/s", "write/s", "readCnt", "p50(us)", "p99(us)", "p999(us)", "max(us)", "stall%", "nf%");
        long stallsBase = 0, notFoundBase = 0;
        long phaseMs = conf.phaseSeconds * 1000L;
        for (int p = 0; p < conf.writeSteps.length; p++) {
            // currentPhase is owned by the ticker (wall clock, identical in both JVMs);
            // here we only wait for this phase's END boundary, then snapshot its bag.
            long acceptedBefore = writeAccepted.get();
            long phaseStartMs = startAtMillis + p * phaseMs;
            long phaseEndMs = phaseStartMs + phaseMs;
            long wait = phaseEndMs - System.currentTimeMillis();
            if (wait > 0) {
                Thread.sleep(wait);
            }
            double dt = Math.max(0.001, (System.currentTimeMillis() - phaseStartMs) / 1000.0);

            // Freeze this phase's read samples: capture each thread's sorted copy ONCE
            // and size `merged` from the actual copied lengths — reading size() and
            // sortedCopy() separately would race with in-flight whenComplete callbacks
            // still appending to this bag. The ticker has already moved readers onto the
            // next phase's bag at the boundary; pause a beat to let stragglers land.
            // stall/notfound counters are cumulative, so this phase's numbers come from
            // differencing against the entry snapshot.
            Thread.sleep(80);
            long[][] perThread = new long[conf.readerThreads][];
            int total = 0;
            long stallsNow = 0, notFoundNow = 0;
            for (int t = 0; t < conf.readerThreads; t++) {
                perThread[t] = readBags[t][p].sortedCopy();
                total += perThread[t].length;
                stallsNow += stallsByThread[t].get();
                notFoundNow += notFoundByThread[t].get();
            }
            long[] merged = new long[total];
            int idx = 0;
            for (int t = 0; t < conf.readerThreads; t++) {
                System.arraycopy(perThread[t], 0, merged, idx, perThread[t].length);
                idx += perThread[t].length;
            }
            Arrays.sort(merged);
            long stalls = stallsNow - stallsBase;
            long notFound = notFoundNow - notFoundBase;
            stallsBase = stallsNow;
            notFoundBase = notFoundNow;
            PhaseStat st = percentiles(merged, stalls, notFound);
            double achievedWrite = (writeAccepted.get() - acceptedBefore) / dt;

            System.out.printf("%-6d %10d %10.0f %10d %10.1f %10.1f %10.1f %10.1f %8.2f %8.2f%n",
                    p, conf.writeSteps[p], achievedWrite, st.count,
                    st.p50 / 1000.0, st.p99 / 1000.0, st.p999 / 1000.0, st.max / 1000.0,
                    st.count > 0 ? 100.0 * st.stalls / st.count : 0.0,
                    st.count > 0 ? 100.0 * st.notFound / st.count : 0.0);
        }
        System.out.printf("%nwrite overlay accepted total=%d failed=%d%n",
                writeAccepted.get(), writeFailed.get());
    }

    private static PhaseStat percentiles(long[] sorted, long stalls, long notFound) {
        PhaseStat s = new PhaseStat();
        s.count = sorted.length;
        s.stalls = stalls;
        s.notFound = notFound;
        if (sorted.length == 0) {
            return s;
        }
        s.p50 = percentile(sorted, 0.50);
        s.p99 = percentile(sorted, 0.99);
        s.p999 = percentile(sorted, 0.999);
        s.max = sorted[sorted.length - 1];
        return s;
    }

    private static long percentile(long[] sorted, double q) {
        int idx = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    private ReferenceConfig<StoreService> openRef(String name) {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(JawsConstants.PROTOCOL_JAWS);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory("netty");
        protocolConfig.setSerialization(conf.serialization);

        ReferenceConfig<StoreService> reference = new ReferenceConfig<>();
        reference.setInterface(StoreService.class);
        reference.setApplication("jaws-iso-bench");
        reference.setModule(name);
        reference.setGroup(StoreBroker.GROUP);
        reference.setVersion(StoreBroker.VERSION);
        reference.setRequestTimeout(conf.requestTimeout);
        reference.setProtocol(protocolConfig);
        reference.setDirectUrl(directUrl);
        return reference;
    }

    private static void joinQuietly(List<Thread> threads) {
        for (Thread t : threads) {
            try {
                t.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void wipeDir(String path) throws Exception {
        File dir = new File(path);
        if (dir.exists()) {
            try (var walk = java.nio.file.Files.walk(dir.toPath())) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> {
                            try {
                                java.nio.file.Files.deleteIfExists(p);
                            } catch (Exception ignored) {
                            }
                        });
            }
        }
        new File(path).mkdirs();
    }
}
