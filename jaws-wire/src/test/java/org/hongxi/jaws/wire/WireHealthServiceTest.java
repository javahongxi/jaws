package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import org.hongxi.jaws.exception.JawsErrorCode;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.wire.health.HealthCheckRequest;
import org.hongxi.jaws.wire.health.HealthCheckResponse;
import org.hongxi.jaws.wire.health.HealthCheckResponse.ServingStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link WireHealthService}: the standard grpc.health.v1
 * Check/Watch semantics (overall status, per-service status, NOT_FOUND for
 * unknown services, Watch streaming of status changes).
 *
 * @author shenhongxi
 */
class WireHealthServiceTest {

    private static HealthCheckResponse check(WireServiceRegistry registry, String service) throws Exception {
        return (HealthCheckResponse) registry
                .resolve("/" + WireHealthService.SERVICE_NAME + "/Check")
                .handle(HealthCheckRequest.newBuilder().setService(service).build());
    }

    private static Flow.Publisher<Message> watch(WireServiceRegistry registry, String service) {
        return registry.resolve("/" + WireHealthService.SERVICE_NAME + "/Watch")
                .handleStream(HealthCheckRequest.newBuilder().setService(service).build());
    }

    /** Collecting subscriber requesting unbounded. */
    private static final class Collector implements Flow.Subscriber<Message> {
        final List<Message> items = new CopyOnWriteArrayList<>();
        volatile Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(Message item) {
            items.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
        }

        @Override
        public void onComplete() {
        }
    }

    private static ServingStatus statusOf(Message message) {
        return ((HealthCheckResponse) message).getStatus();
    }

    @Test
    void overallStatusIsServingByDefault() throws Exception {
        WireHealthService health = new WireHealthService();
        WireServiceRegistry registry = new WireServiceRegistry();
        health.registerTo(registry);

        assertEquals(ServingStatus.SERVING, check(registry, "").getStatus());
    }

    @Test
    void unknownServiceCheckFailsWithNotFound() {
        WireHealthService health = new WireHealthService();
        WireServiceRegistry registry = new WireServiceRegistry();
        health.registerTo(registry);

        JawsServiceException e = assertThrows(JawsServiceException.class,
                () -> check(registry, "no.such.Service"));
        assertEquals(JawsErrorCode.SERVICE_NOT_FOUND, e.getErrorCode());
        // ...which maps to grpc-status NOT_FOUND for standard clients
        assertEquals(WireConstants.STATUS_NOT_FOUND, WireStatus.fromThrowable(e));
    }

    @Test
    void setStatusIsVisibleToCheck() throws Exception {
        WireHealthService health = new WireHealthService();
        WireServiceRegistry registry = new WireServiceRegistry();
        health.registerTo(registry);

        health.setStatus("svc.A", ServingStatus.SERVING);
        assertEquals(ServingStatus.SERVING, check(registry, "svc.A").getStatus());

        health.setStatus("svc.A", ServingStatus.NOT_SERVING);
        assertEquals(ServingStatus.NOT_SERVING, check(registry, "svc.A").getStatus());

        // Overall can be flipped too (e.g. during graceful shutdown)
        health.setStatus("", ServingStatus.NOT_SERVING);
        assertEquals(ServingStatus.NOT_SERVING, check(registry, "").getStatus());
    }

    @Test
    void watchEmitsInitialStatusAndUpdates() {
        WireHealthService health = new WireHealthService();
        WireServiceRegistry registry = new WireServiceRegistry();
        health.registerTo(registry);

        Flow.Publisher<Message> publisher = watch(registry, "svc.B");
        Collector collector = new Collector();
        publisher.subscribe(collector);

        // Unknown service initially: SERVICE_UNKNOWN per the protocol spec
        assertEquals(1, collector.items.size());
        assertEquals(ServingStatus.SERVICE_UNKNOWN, statusOf(collector.items.get(0)));

        health.setStatus("svc.B", ServingStatus.SERVING);
        health.setStatus("svc.B", ServingStatus.NOT_SERVING);
        assertEquals(3, collector.items.size());
        assertEquals(ServingStatus.SERVING, statusOf(collector.items.get(1)));
        assertEquals(ServingStatus.NOT_SERVING, statusOf(collector.items.get(2)));
    }

    @Test
    void watchOfExistingServiceEmitsCurrentStatusFirst() {
        WireHealthService health = new WireHealthService();
        WireServiceRegistry registry = new WireServiceRegistry();
        health.registerTo(registry);
        health.setStatus("svc.C", ServingStatus.SERVING);

        Collector collector = new Collector();
        watch(registry, "svc.C").subscribe(collector);
        assertEquals(1, collector.items.size());
        assertEquals(ServingStatus.SERVING, statusOf(collector.items.get(0)));
    }

    @Test
    void clearStatusNotifiesWatchersWithServiceUnknown() {
        WireHealthService health = new WireHealthService();
        WireServiceRegistry registry = new WireServiceRegistry();
        health.registerTo(registry);
        health.setStatus("svc.D", ServingStatus.SERVING);

        Collector collector = new Collector();
        watch(registry, "svc.D").subscribe(collector);

        health.clearStatus("svc.D");
        assertEquals(2, collector.items.size());
        assertEquals(ServingStatus.SERVICE_UNKNOWN, statusOf(collector.items.get(1)));
    }

    @Test
    void canceledWatchStopsReceivingUpdates() {
        WireHealthService health = new WireHealthService();
        WireServiceRegistry registry = new WireServiceRegistry();
        health.registerTo(registry);
        health.setStatus("svc.E", ServingStatus.SERVING);

        Collector collector = new Collector();
        watch(registry, "svc.E").subscribe(collector);
        assertEquals(1, collector.items.size());

        collector.subscription.cancel();
        health.setStatus("svc.E", ServingStatus.NOT_SERVING);
        assertEquals(1, collector.items.size(), "no updates after cancel");
    }
}
