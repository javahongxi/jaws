package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import org.hongxi.jaws.exception.JawsErrorCode;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.wire.health.HealthCheckRequest;
import org.hongxi.jaws.wire.health.HealthCheckResponse;
import org.hongxi.jaws.wire.health.HealthCheckResponse.ServingStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;

/**
 * Standard gRPC health checking service ({@code grpc.health.v1.Health},
 * <a href="https://github.com/grpc/grpc/blob/master/doc/health-checking.md">
 * health-checking.md</a>).
 * <p>
 * {@link WireServer} creates and registers a {@code WireHealthService}
 * automatically — both in direct API mode (registered into the
 * {@link WireHandlerRegistry}) and in Provider pipeline mode (intercepted at
 * the stream-handler level). Use {@link WireServer#getHealthService()} to
 * obtain the instance for managing per-service statuses.
 * <p>
 * Serves the two protocol methods:
 * <ul>
 *   <li>{@code Check} — unary; the empty service name reports the overall
 *       server status, an unknown service name fails with NOT_FOUND per spec</li>
 *   <li>{@code Watch} — server streaming; emits the current status of the
 *       requested service and every subsequent change until the caller
 *       cancels</li>
 * </ul>
 * <p>
 * Standard tooling such as {@code grpc_health_probe} or
 * {@code grpcurl ... grpc.health.v1.Health/Check} can then probe the server.
 *
 * @author shenhongxi
 */
public class WireHealthService {

    /** Fully-qualified service name used in the gRPC path. */
    public static final String SERVICE_NAME = "grpc.health.v1.Health";

    /** The empty service name addresses the overall server health. */
    private static final String OVERALL = "";

    /** Per-service status; the overall status is stored under {@link #OVERALL}. */
    private final ConcurrentMap<String, ServingStatus> statuses = new ConcurrentHashMap<>();
    /** Active Watch streams, notified on every status change. */
    private final List<WatchPublisher> watchers = new CopyOnWriteArrayList<>();

    private final WireMethodHandler checkHandler = new CheckHandler();
    private final WireMethodHandler watchHandler = new WatchHandler();

    public WireHealthService() {
        // The server is serving by default until told otherwise
        statuses.put(OVERALL, ServingStatus.SERVING);
    }

    /**
     * Register the Check / Watch method handlers under the standard
     * {@code grpc.health.v1.Health} path.
     */
    public void registerTo(WireHandlerRegistry registry) {
        registry.register(SERVICE_NAME, "Check", checkHandler);
        registry.register(SERVICE_NAME, "Watch", watchHandler);
    }

    /**
     * Update the serving status of a service (use the empty string for the
     * overall server status) and push the change to all Watch streams of
     * that service.
     */
    public void setStatus(String service, ServingStatus status) {
        String key = normalize(service);
        statuses.put(key, status);
        notifyWatchers(key, status);
    }

    /**
     * Remove the status of a service; Watch streams of that service are
     * notified with SERVICE_UNKNOWN per the protocol spec. The overall
     * status cannot be cleared.
     */
    public void clearStatus(String service) {
        String key = normalize(service);
        if (OVERALL.equals(key)) {
            return;
        }
        if (statuses.remove(key) != null) {
            notifyWatchers(key, ServingStatus.SERVICE_UNKNOWN);
        }
    }

    /**
     * @param service the service name (empty string = overall server)
     * @return the current status, or {@code null} if the service is unknown
     */
    ServingStatus getStatus(String service) {
        return statuses.get(normalize(service));
    }

    private static String normalize(String service) {
        return service == null ? OVERALL : service;
    }

    private void notifyWatchers(String service, ServingStatus status) {
        Message update = HealthCheckResponse.newBuilder().setStatus(status).build();
        for (WatchPublisher publisher : watchers) {
            if (publisher.service.equals(service)) {
                publisher.offer(update);
            }
        }
    }

    /**
     * {@code Check(HealthCheckRequest) returns (HealthCheckResponse)}.
     * Unknown services fail with grpc-status NOT_FOUND per the protocol spec.
     */
    private class CheckHandler implements WireMethodHandler {
        @Override
        public Message handle(Message request) {
            String service = ((HealthCheckRequest) request).getService();
            ServingStatus status = statuses.get(service);
            if (status == null) {
                throw new JawsServiceException("Unknown health service: " + service,
                        JawsErrorCode.SERVICE_NOT_FOUND);
            }
            return HealthCheckResponse.newBuilder().setStatus(status).build();
        }

        @Override
        public Parser<? extends Message> getRequestParser() {
            return HealthCheckRequest.parser();
        }
    }

    /**
     * {@code Watch(HealthCheckRequest) returns (stream HealthCheckResponse)}.
     * Emits the current status immediately and every subsequent change.
     */
    private class WatchHandler implements WireMethodHandler {
        @Override
        public MethodType methodType() {
            return MethodType.SERVER_STREAMING;
        }

        @Override
        public Message handle(Message request) {
            throw new UnsupportedOperationException("Watch is a streaming method");
        }

        @Override
        public Flow.Publisher<Message> handleStream(Message request) {
            String service = ((HealthCheckRequest) request).getService();
            WatchPublisher publisher = new WatchPublisher(normalize(service));
            watchers.add(publisher);
            return publisher;
        }

        @Override
        public Parser<? extends Message> getRequestParser() {
            return HealthCheckRequest.parser();
        }
    }

    /**
     * Per-Watch-call publisher buffering status messages until the single
     * subscriber consumes them; deregisters itself on cancel so closed
     * streams do not accumulate.
     */
    private class WatchPublisher implements Flow.Publisher<Message> {
        private final String service;
        /** Guarded by {@code this}: one entry per subscription (only one here). */
        private final List<WatchSubscription> subscriptions = new ArrayList<>();

        WatchPublisher(String service) {
            this.service = service;
        }

        void offer(Message statusMessage) {
            List<WatchSubscription> snapshot;
            synchronized (this) {
                snapshot = new ArrayList<>(subscriptions);
            }
            for (WatchSubscription sub : snapshot) {
                sub.offer(statusMessage);
            }
        }

        @Override
        public synchronized void subscribe(Flow.Subscriber<? super Message> subscriber) {
            WatchSubscription sub = new WatchSubscription(this, subscriber);
            subscriptions.add(sub);
            // Emit the current status immediately (SERVICE_UNKNOWN if the
            // service does not exist yet, per the protocol spec)
            ServingStatus current = statuses.get(service);
            sub.offer(HealthCheckResponse.newBuilder()
                    .setStatus(current != null ? current : ServingStatus.SERVICE_UNKNOWN)
                    .build());
            subscriber.onSubscribe(sub);
        }

        synchronized void remove(WatchSubscription sub) {
            subscriptions.remove(sub);
            if (subscriptions.isEmpty()) {
                watchers.remove(this);
            }
        }
    }

    /**
     * Single-subscriber delivery queue with re-entrancy-safe draining,
     * mirroring the buffering contract expected by the server streaming
     * dispatcher (items emitted before subscription must not be dropped).
     */
    private class WatchSubscription implements Flow.Subscription {
        private final WatchPublisher publisher;
        private final Flow.Subscriber<? super Message> subscriber;
        private final Deque<Message> pending = new ArrayDeque<>();
        private boolean canceled;
        private boolean draining;

        WatchSubscription(WatchPublisher publisher, Flow.Subscriber<? super Message> subscriber) {
            this.publisher = publisher;
            this.subscriber = subscriber;
        }

        @Override
        public void request(long n) {
            drain();
        }

        @Override
        public void cancel() {
            synchronized (this) {
                canceled = true;
            }
            // Deregister so an abandoned Watch stream does not accumulate
            // in the health service's watcher list
            publisher.remove(this);
        }

        void offer(Message message) {
            synchronized (this) {
                if (canceled) {
                    return;
                }
                pending.add(message);
            }
            drain();
        }

        private void drain() {
            synchronized (this) {
                if (draining || canceled) {
                    return;
                }
                draining = true;
            }
            try {
                while (true) {
                    Message next;
                    synchronized (this) {
                        next = pending.poll();
                    }
                    if (next == null) {
                        return;
                    }
                    subscriber.onNext(next);
                }
            } finally {
                synchronized (this) {
                    draining = false;
                    if (!canceled && !pending.isEmpty()) {
                        drain();
                    }
                }
            }
        }
    }
}
