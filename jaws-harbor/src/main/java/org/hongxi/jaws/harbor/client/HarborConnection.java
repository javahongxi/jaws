package org.hongxi.jaws.harbor.client;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.util.NetUtils;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.harbor.HarborProtocol;
import org.hongxi.jaws.harbor.model.Request;
import org.hongxi.jaws.harbor.model.Response;
import org.hongxi.jaws.harbor.model.request.ConnectionSetupRequest;
import org.hongxi.jaws.harbor.model.request.HealthCheckRequest;
import org.hongxi.jaws.harbor.model.request.NotifySubscriberRequest;
import org.hongxi.jaws.harbor.model.request.SetupAckRequest;
import org.hongxi.jaws.harbor.model.response.HealthCheckResponse;
import org.hongxi.jaws.harbor.proto.Payload;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;
import org.hongxi.jaws.transport.StreamSubject;
import org.hongxi.jaws.wire.WireClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The client side of one harbor connection: a bidirectional stream carrying
 * setup and pushes, plus the unary calls that share the TCP connection.
 * <p>
 * Ownership of the notification stream is the whole reason this class exists.
 * Harbor keys a subscriber to the id it mints per TCP connection, so a
 * reconnect silently orphans every subscription and every registration the
 * previous connection owned. {@link #recover()} therefore does not just reopen
 * the stream — once the fresh setup is acknowledged it runs the replay hook the
 * client supplied, which re-registers and re-subscribes.
 *
 * @author shenhongxi
 */
final class HarborConnection implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(HarborConnection.class);

    /** Sent in the setup frame; also how tests recognise our sessions server-side. */
    static final String CLIENT_VERSION = "jaws-harbor-client/1.0";

    private final HarborClientConfig config;
    private final String clientIp;
    private final WireClient wireClient;
    private final Consumer<Payload> pushSink;
    private final Runnable replayHook;
    private final AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());

    private final ScheduledExecutorService keepAliveScheduler;

    /** Replaced on every attempt: a completed subject cannot be reopened. */
    private volatile StreamSubject<Object> outbound;
    private volatile CompletableFuture<Void> setupAck;
    private volatile boolean closed;

    HarborConnection(HarborClientConfig config,
                     Consumer<Payload> pushSink,
                     Runnable replayHook) {
        this.config = config;
        this.clientIp = NetUtils.getLocalAddress(Map.of(config.host(), config.port())).getHostAddress();
        this.pushSink = pushSink;
        this.replayHook = replayHook;
        this.wireClient = new WireClient(buildUrl(config));
        this.keepAliveScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "harbor-client-keepalive");
            thread.setDaemon(true);
            return thread;
        });
    }

    void start() {
        if (!wireClient.open()) {
            throw new JawsServiceException("Cannot connect to harbor "
                    + config.host() + ":" + config.port());
        }
        openNotificationStream();
        keepAliveScheduler.scheduleWithFixedDelay(this::keepAliveTick,
                config.keepAliveMillis(), config.keepAliveMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Send one unary request and read the typed reply. A call that fails at the
     * connection level triggers recovery and one retry, so callers see the same
     * outcome whether the stream died a moment ago or is dying now.
     */
    <T extends Response> T call(Request request, Class<T> responseType) {
        T response = tryCall(request, responseType);
        if (!response.isSuccess()) {
            throw new JawsServiceException("harbor rejected "
                    + HarborProtocol.typeToken(request.getClass()) + ": resultCode="
                    + response.getResultCode());
        }
        return response;
    }

    private <T extends Response> T tryCall(Request request, Class<T> responseType) {
        try {
            return doCall(request, responseType);
        } catch (JawsServiceException e) {
            log.warn("[harbor-client] call {} failed: {}, recovering and retrying once",
                    HarborProtocol.typeToken(request.getClass()), e.getMessage());
            recover();
            return doCall(request, responseType);
        }
    }

    private <T extends Response> T doCall(Request request, Class<T> responseType) {
        DefaultRequest rpcRequest = new DefaultRequest();
        rpcRequest.setInterfaceName(HarborProtocol.RPC_UNARY_SERVICE);
        rpcRequest.setMethodName(HarborProtocol.RPC_UNARY_METHOD);
        rpcRequest.setArguments(new Object[]{HarborProtocol.encodeRequest(request)});
        try {
            org.hongxi.jaws.rpc.Response response = wireClient.request(rpcRequest,
                    Payload.getDefaultInstance().getParserForType());
            if (!(response.getValue() instanceof Payload payload)) {
                throw new JawsServiceException("unexpected reply from harbor: "
                        + response.getValue());
            }
            markActive();
            return HarborProtocol.parseBody(payload, responseType);
        } catch (JawsServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new JawsServiceException("harbor call failed: "
                    + HarborProtocol.typeToken(request.getClass()), e);
        }
    }

    /**
     * Cheap liveness probe, also the idle keep-alive message itself.
     */
    boolean serverHealthy() {
        try {
            call(new HealthCheckRequest(), HealthCheckResponse.class);
            return true;
        } catch (Exception e) {
            log.debug("[harbor-client] health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Reopen the notification stream and replay whatever the old connection
     * owned. Idempotent: concurrent callers take turns, and a stream that is
     * already up is left alone unless asked to re-establish.
     */
    synchronized void recover() {
        if (closed) {
            return;
        }
        // End the old request stream first. Its absence is how harbor learns the
        // previous connection is gone; skipping it would leave that session — and
        // everything registered under its id — to age out in the watchdog instead.
        StreamSubject<Object> previous = outbound;
        if (previous != null) {
            try {
                previous.onCompleted();
            } catch (Exception e) {
                log.debug("[harbor-client] old stream already dead: {}", e.getMessage());
            }
        }
        try {
            openNotificationStream();
        } catch (Exception e) {
            throw new JawsServiceException("cannot re-establish harbor notification stream", e);
        }
        replayHook.run();
    }

    private void openNotificationStream() {
        StreamSubject<Object> requestStream = new StreamSubject<>();
        CompletableFuture<Void> ack = new CompletableFuture<>();
        this.outbound = requestStream;
        this.setupAck = ack;

        DefaultRequest rpcRequest = new DefaultRequest();
        rpcRequest.setInterfaceName(HarborProtocol.RPC_STREAM_SERVICE);
        rpcRequest.setMethodName(HarborProtocol.RPC_STREAM_METHOD);
        rpcRequest.setArguments(new Object[0]);

        StreamSource<Object> inbound = wireClient.requestBidiStream(rpcRequest, requestStream,
                Payload.getDefaultInstance().getParserForType());
        inbound.subscribe(new StreamObserver<>() {
            @Override
            public void onNext(Object item) {
                if (item instanceof Payload payload) {
                    handleFrame(payload);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                log.info("[harbor-client] notification stream error: {}",
                        throwable.getMessage());
            }

            @Override
            public void onCompleted() {
                log.info("[harbor-client] notification stream closed by harbor");
            }
        });

        // Setup first: harbor only records a connection (and so only accepts a
        // subscriber) once this frame carries our address.
        ConnectionSetupRequest setup = new ConnectionSetupRequest();
        setup.setNamespace(config.namespace());
        setup.setClientVersion(CLIENT_VERSION);
        setup.setTenant(config.namespace());
        setup.setLabels(Map.of("source", "sdk", "module", "naming"));
        requestStream.onNext(HarborProtocol.encodeRequest(setup, clientIp));
        markActive();

        try {
            ack.get(config.setupTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new JawsServiceException("harbor did not acknowledge the setup within "
                    + config.setupTimeoutMillis() + "ms", e);
        }
    }

    private void handleFrame(Payload payload) {
        String type = payload.getMetadata().getType();
        if (HarborProtocol.typeToken(SetupAckRequest.class).equals(type)) {
            CompletableFuture<Void> pending = setupAck;
            if (pending != null) {
                pending.complete(null);
            }
            return;
        }
        if (HarborProtocol.typeToken(NotifySubscriberRequest.class).equals(type)) {
            markActive();
            pushSink.accept(payload);
        }
        // A NotifySubscriberResponse ack is deliberately not sent: harbor's push
        // is latest-state idempotent convergence with no delivery signal, so an
        // ack would only add traffic. The class exists for the frames nacos
        // clients send back.
    }

    private void keepAliveTick() {
        if (closed) {
            return;
        }
        // Only silence needs a message; anything the caller sent recently already
        // refreshed the server's activity clock.
        if (System.currentTimeMillis() - lastActivity.get() < config.keepAliveMillis()) {
            return;
        }
        if (serverHealthy()) {
            return;
        }
        log.warn("[harbor-client] server unreachable, attempting recovery");
        try {
            recover();
        } catch (Exception e) {
            log.warn("[harbor-client] recovery failed: {}", e.getMessage());
        }
    }

    private void markActive() {
        lastActivity.set(System.currentTimeMillis());
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        keepAliveScheduler.shutdownNow();
        // Ending the request stream is what tells harbor we are gone: it runs the
        // same closure transaction as a dropped channel, so no explicit
        // deregistration is needed on the way out.
        StreamSubject<Object> stream = outbound;
        if (stream != null) {
            stream.onCompleted();
        }
        wireClient.close();
    }

    private static URL buildUrl(HarborClientConfig config) {
        URL url = new URL("wire", config.host(), config.port(),
                HarborProtocol.RPC_UNARY_SERVICE);
        url.addParameter(UrlParam.Transport.REQUEST_TIMEOUT.getName(),
                String.valueOf(config.requestTimeoutMillis()));
        url.addParameter(UrlParam.Transport.CONNECT_TIMEOUT.getName(),
                String.valueOf(config.connectTimeoutMillis()));
        // One attempt per call: replaying a registration or subscription after a
        // reconnect is the connection's job, not the transport's.
        url.addParameter(UrlParam.Transport.RETRY_MAX_ATTEMPTS.getName(), "1");
        return url;
    }
}
