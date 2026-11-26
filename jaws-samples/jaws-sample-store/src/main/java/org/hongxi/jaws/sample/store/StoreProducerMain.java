package org.hongxi.jaws.sample.store;

import org.hongxi.jaws.store.PutResult;
import org.hongxi.jaws.store.StoreMessage;
import org.hongxi.jaws.store.client.StoreProducer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Store producer demo with a throughput readout.
 *
 * <pre>
 * Properties:
 *   -DdirectUrl=127.0.0.1:10911   broker address (direct mode)
 *   -Dregistry=harbor://127.0.0.1:19848   registry mode, overrides directUrl
 *   -DloadBalance=consistentHash  pin one queue to one broker (registry mode)
 *   -Dcount=1000                  messages to send
 *   -Dmode=sync|async|oneway      send semantics (default sync)
 *   -Dtopic=store-demo            target topic, queue 0
 * </pre>
 */
public class StoreProducerMain {

    public static void main(String[] args) throws Exception {
        String directUrl = System.getProperty("directUrl", "127.0.0.1:10911");
        int count = Integer.parseInt(System.getProperty("count", "1000"));
        String mode = System.getProperty("mode", "sync");
        String topic = System.getProperty("topic", "store-demo");

        String registry = System.getProperty("registry");
        StoreProducer producer = registry == null
                ? new StoreProducer(directUrl)
                : new StoreProducer(List.of(StoreSampleRegistry.parse(registry)), "fastjson2", 3000,
                        StoreProducer.DEFAULT_MAX_INFLIGHT, System.getProperty("loadBalance"));
        long begin = System.nanoTime();
        switch (mode) {
            case "async" -> {
                List<CompletableFuture<PutResult>> futures = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    futures.add(producer.sendAsync(StoreMessage.of(topic, 0, "msg-" + i)));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                for (CompletableFuture<PutResult> future : futures) {
                    requireOk(future.join());
                }
            }
            case "oneway" -> {
                for (int i = 0; i < count; i++) {
                    producer.sendOneway(StoreMessage.of(topic, 0, "msg-" + i));
                }
                // no completion signal by definition; give in-flight requests
                // a moment to drain before the process exits
                Thread.sleep(2000);
            }
            default -> {
                for (int i = 0; i < count; i++) {
                    requireOk(producer.sendSync(StoreMessage.of(topic, 0, "msg-" + i)));
                }
            }
        }
        long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;
        System.out.printf("Sent %d messages (%s) in %d ms, %.0f msg/s%n",
                count, mode, elapsedMillis, count * 1000.0 / Math.max(1, elapsedMillis));
        producer.shutdown();
    }

    private static void requireOk(PutResult result) {
        if (!result.isOk()) {
            throw new IllegalStateException("Put rejected: " + result.getStatus());
        }
    }
}