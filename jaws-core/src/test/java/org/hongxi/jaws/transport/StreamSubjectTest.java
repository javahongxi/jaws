package org.hongxi.jaws.transport;

import org.hongxi.jaws.stream.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link StreamSubject}, focusing on the subscribe-after-buffered-items
 * race: in the RPC streaming flow, the application subscribes after
 * {@code requestStream()} returns, while the Netty event loop may have
 * already delivered items into the buffer. A subscriber must receive ALL items,
 * including those that arrived before subscription.
 *
 * @author shenhongxi
 */
class StreamSubjectTest {

    private static final class CollectingObserver implements StreamObserver<Object> {
        final List<Object> received = new CopyOnWriteArrayList<>();
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();

        @Override
        public void onNext(Object item) {
            received.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            error.set(throwable);
            completed.countDown();
        }

        @Override
        public void onCompleted() {
            completed.countDown();
        }
    }

    @Test
    void itemsBufferedBeforeSubscribeMustBeDelivered() throws Exception {
        StreamSubject<Object> observer = new StreamSubject<>();

        // Simulate the race: server items arrive on the event loop BEFORE the
        // application subscribes (requestStream has already returned).
        observer.onNext("item-1");
        observer.onNext("item-2");

        CollectingObserver consumer = new CollectingObserver();
        observer.subscribe(consumer);

        observer.onNext("item-3");
        observer.onCompleted();

        assertTrue(consumer.completed.await(5, TimeUnit.SECONDS), "stream should complete");
        assertNull(consumer.error.get());
        assertEquals(3, consumer.received.size(), "all items including pre-subscribe ones must be delivered");
        assertEquals("item-1", consumer.received.get(0));
        assertEquals("item-2", consumer.received.get(1));
        assertEquals("item-3", consumer.received.get(2));
    }

    @Test
    void subscribeAfterCompleteMustStillDeliverAllItems() throws Exception {
        StreamSubject<Object> observer = new StreamSubject<>();
        observer.onNext("item-1");
        observer.onNext("item-2");
        observer.onCompleted();

        // Subscribe after the entire stream has finished — late subscriber must
        // still receive the full replay.
        CollectingObserver consumer = new CollectingObserver();
        observer.subscribe(consumer);

        assertTrue(consumer.completed.await(5, TimeUnit.SECONDS), "stream should complete immediately");
        assertNull(consumer.error.get());
        assertEquals(2, consumer.received.size());
    }

    @Test
    void errorDeliveredToLateSubscriber() throws Exception {
        StreamSubject<Object> observer = new StreamSubject<>();
        observer.onNext("item-1");
        observer.onError(new RuntimeException("boom"));

        CollectingObserver consumer = new CollectingObserver();
        observer.subscribe(consumer);

        assertTrue(consumer.completed.await(5, TimeUnit.SECONDS), "error should be delivered");
        assertEquals("boom", consumer.error.get().getMessage());
        assertEquals(1, consumer.received.size(), "items before the error must still be delivered");
    }
}
