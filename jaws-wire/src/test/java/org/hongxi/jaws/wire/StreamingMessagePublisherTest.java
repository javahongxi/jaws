package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import com.google.protobuf.StringValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link StreamingMessagePublisher}, focusing on the subscribe-after-buffered-items
 * race: in the RPC streaming flow, the application subscribes after
 * {@code WireClient.requestStream()} returns, while the Netty event loop may have
 * already delivered items into the buffer. A subscriber must receive ALL items,
 * including those that arrived before subscription.
 *
 * @author shenhongxi
 */
class StreamingMessagePublisherTest {

    private static final class CollectingSubscriber implements Flow.Subscriber<Message> {
        final List<Message> received = new CopyOnWriteArrayList<>();
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        volatile Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(Message item) {
            received.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            error.set(throwable);
            completed.countDown();
        }

        @Override
        public void onComplete() {
            completed.countDown();
        }
    }

    @Test
    void itemsBufferedBeforeSubscribeMustBeDelivered() throws Exception {
        StreamingMessagePublisher publisher = new StreamingMessagePublisher();

        // Simulate the race: server items arrive on the event loop BEFORE the
        // application subscribes (requestStream has already returned).
        publisher.addItem(StringValue.of("#1"));
        publisher.addItem(StringValue.of("#2"));

        CollectingSubscriber subscriber = new CollectingSubscriber();
        publisher.subscribe(subscriber);

        publisher.addItem(StringValue.of("#3"));
        publisher.complete();

        assertTrue(subscriber.completed.await(5, TimeUnit.SECONDS), "stream should complete");
        assertNull(subscriber.error.get());
        assertEquals(3, subscriber.received.size(), "all items including pre-subscribe ones must be delivered");
        assertEquals("#1", ((StringValue) subscriber.received.get(0)).getValue());
        assertEquals("#2", ((StringValue) subscriber.received.get(1)).getValue());
        assertEquals("#3", ((StringValue) subscriber.received.get(2)).getValue());
    }

    @Test
    void subscribeAfterCompleteMustStillDeliverAllItems() throws Exception {
        StreamingMessagePublisher publisher = new StreamingMessagePublisher();
        publisher.addItem(StringValue.of("#1"));
        publisher.addItem(StringValue.of("#2"));
        publisher.complete();

        // Subscribe after the entire stream has finished — late subscriber must
        // still receive the full replay (this is what grpcurl-style sync consumption looks like).
        CollectingSubscriber subscriber = new CollectingSubscriber();
        publisher.subscribe(subscriber);

        assertTrue(subscriber.completed.await(5, TimeUnit.SECONDS), "stream should complete immediately");
        assertNull(subscriber.error.get());
        assertEquals(2, subscriber.received.size());
    }

    @Test
    void errorDeliveredToLateSubscriber() throws Exception {
        StreamingMessagePublisher publisher = new StreamingMessagePublisher();
        publisher.addItem(StringValue.of("#1"));
        publisher.completeExceptionally(new RuntimeException("boom"));

        CollectingSubscriber subscriber = new CollectingSubscriber();
        publisher.subscribe(subscriber);

        assertTrue(subscriber.completed.await(5, TimeUnit.SECONDS), "error should be delivered");
        assertEquals("boom", subscriber.error.get().getMessage());
        assertEquals(1, subscriber.received.size(), "items before the error must still be delivered");
    }
}
