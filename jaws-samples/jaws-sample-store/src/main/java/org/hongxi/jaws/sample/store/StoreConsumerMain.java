package org.hongxi.jaws.sample.store;

import org.hongxi.jaws.store.client.StoreConsumer;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Store consumer demo: subscribes statically to one queue, counts messages
 * and exits once the expected count arrives (or the timeout fires).
 *
 * <pre>
 * Properties:
 *   -DdirectUrl=127.0.0.1:10911   broker address(es), comma-separated
 *   -Dregistry=harbor://127.0.0.1:19848   registry mode, overrides directUrl
 *   -Dexpected=1000               messages to wait for
 *   -Dgroup=store-demo-group      consumer group (broker-side offset key)
 *   -Dtopic=store-demo            source topic, queue 0
 * </pre>
 *
 * <p>Progress lives on the broker, so a re-run resumes where the group left off.
 * A group the broker has never seen starts at the queue tail and skips whatever
 * is already in the log — start this consumer before the producer when the demo
 * needs every message (which is also what the long-polling hold wants).
 */
public class StoreConsumerMain {

    public static void main(String[] args) throws Exception {
        String directUrl = System.getProperty("directUrl", "127.0.0.1:10911");
        int expected = Integer.parseInt(System.getProperty("expected", "1000"));
        String group = System.getProperty("group", "store-demo-group");
        String topic = System.getProperty("topic", "store-demo");

        AtomicInteger received = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(expected);
        String registry = System.getProperty("registry");
        StoreConsumer consumer = registry == null
                ? new StoreConsumer(directUrl, group)
                : new StoreConsumer(List.of(StoreSampleRegistry.parse(registry)), group);
        consumer.subscribe(topic, 0, messages -> {
            received.addAndGet(messages.size());
            messages.forEach(m -> latch.countDown());
        });
        consumer.start();
        System.out.println("Consumer started, waiting for " + expected + " messages ...");

        boolean complete = latch.await(30, TimeUnit.SECONDS);
        System.out.println("Received " + received.get() + " message(s), complete=" + complete);
        consumer.shutdown();
        if (!complete) {
            System.exit(1);
        }
    }
}