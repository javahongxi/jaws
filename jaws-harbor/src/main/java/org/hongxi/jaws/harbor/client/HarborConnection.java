package org.hongxi.jaws.harbor.client;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.util.NetUtils;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.harbor.HarborProtocol;
import org.hongxi.jaws.harbor.model.Request;
import org.hongxi.jaws.harbor.model.Response;
import org.hongxi.jaws.harbor.model.request.ConnectResetRequest;
import org.hongxi.jaws.harbor.model.request.ConnectionSetupRequest;
import org.hongxi.jaws.harbor.model.request.DynamicConfigChangeRequest;
import org.hongxi.jaws.harbor.model.request.HealthCheckRequest;
import org.hongxi.jaws.harbor.model.request.NotifySubscriberRequest;
import org.hongxi.jaws.harbor.model.request.ServerCheckRequest;
import org.hongxi.jaws.harbor.model.request.SetupAckRequest;
import org.hongxi.jaws.harbor.model.response.ConnectResetResponse;
import org.hongxi.jaws.harbor.model.response.HealthCheckResponse;
import org.hongxi.jaws.harbor.model.response.ServerCheckResponse;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
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
    static final String CLIENT_VERSION = HarborProtocol.NATIVE_CLIENT_VERSION;

    private final HarborClientConfig config;
    private final String clientIp;

    /** Every node this client may attach to, the configured primary first. */
    private final List<String> targets;

    /**
     * Rotation cursor over {@link #targets}, seeded randomly: Nacos
     * {@code NamingServerListManager.start()} does the same so that a hundred
     * provider processes do not all land on the first entry and only scatter after
     * it dies.
     */
    private volatile int cursor;

    /** The address we are attached to; a redirect or a failover moves it. */
    private volatile String host;
    private volatile int port;

    /**
     * The id the node assigned us for the current connection, minted per TCP link at
     * {@link #establish()}. Kept purely as our half of the log correlation the server
     * does under {@code connId=}: every later recovery and the close name the same id,
     * so one connection can be traced end to end across both sides' logs.
     */
    private volatile String connectionId;

    private volatile WireClient wireClient;
    private final Consumer<Payload> pushSink;
    private final Consumer<Payload> configSink;
    private final Runnable replayHook;
    private final AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());

    private final ScheduledExecutorService keepAliveScheduler;

    /** Replaced on every attempt: a completed subject cannot be reopened. */
    private volatile StreamSubject<Object> outbound;
    private volatile CompletableFuture<Void> setupAck;

    private volatile boolean closed;

    HarborConnection(HarborClientConfig config,
                     Consumer<Payload> pushSink,
                     Consumer<Payload> configSink,
                     Runnable replayHook) {
        this.config = config;
        this.clientIp = resolveClientIp();
        this.pushSink = pushSink;
        this.configSink = configSink;
        this.replayHook = replayHook;
        this.targets = config.allAddresses();
        this.cursor = startCursor(targets.size());
        String[] first = targets.get(cursor).split(":");
        this.host = first[0];
        this.port = Integer.parseInt(first[1]);
        this.keepAliveScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "harbor-client-keepalive");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * The local address the registry should see us on: probe which NIC can reach the
     * primary, so a multi-homed host reports the right one rather than a loopback
     * default. Configured as data on the client; the probe belongs here, with the
     * endpoint it resolves.
     */
    private String resolveClientIp() {
        String[] primary = config.primary().split(":");
        return NetUtils.getLocalAddress(Map.of(primary[0], Integer.parseInt(primary[1])))
                .getHostAddress();
    }

    void start() {
        // Same walk as recovery: a node list may well hold a node that is not up yet
        // (a cluster coming online, a backup address kept for failover), and choosing
        // a random start must not turn that into a failed startup.
        establish();
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
            // doCall, not call: probing is what decides whether to recover, so it must
            // not itself trigger a recovery.
            doCall(new HealthCheckRequest(), HealthCheckResponse.class);
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
        log.info("[harbor-client] recovering connection connId={}", connectionId);
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

        // A node that is alive but whose stream broke needs no new channel: probing it
        // first keeps a transient failure from costing a TCP round trip — and keeps our
        // identity, which is minted per connection, from changing under the registry.
        if (serverHealthy() && reopenStreamInPlace()) {
            replayHook.run();
            return;
        }

        // Otherwise walk the node list, which is what makes losing a node survivable.
        establish();
        replayHook.run();
    }

    /**
     * Attach to the first node that answers — TCP up, server check passed — and open
     * its notification stream: the one we are on first, then the rest of the list
     * from the cursor. Failing all of them is reported as one event listing every
     * address tried.
     */
    private void establish() {
        List<String> candidates = candidateOrder();
        String attachedBefore = host + ":" + port;
        Exception last = null;
        for (String candidate : candidates) {
            try {
                attachTo(candidate);
                serverCheck();
                openNotificationStream();
                if (candidate.equals(attachedBefore)) {
                    log.info("[harbor-client] attached to {} as connId={}", candidate,
                            connectionId);
                } else {
                    log.warn("[harbor-client] attached to {} as connId={} instead of {}",
                            candidate, connectionId, attachedBefore);
                }
                return;
            } catch (Exception e) {
                last = e;
                log.warn("[harbor-client] cannot attach to {}: {}", candidate, e.getMessage());
            }
        }
        throw new JawsServiceException("no harbor node reachable out of " + candidates.size()
                + " (" + candidates + ")", last);
    }

    /**
     * The unary handshake nacos-client performs before it opens a stream: a node
     * must answer {@link ServerCheckRequest} with a connection id before we commit
     * to it. Run from {@link #establish()} only, once per fresh TCP connection —
     * the recover fast path reopens a stream on a channel that already passed this.
     * A rejection is what moves the candidate walk on, so a port that is open but
     * not harbor is turned away here rather than at the slower setup-ack timeout. A
     * passing check records the assigned id on {@link #connectionId}, which the rest
     * of this connection's logs then carry.
     */
    private void serverCheck() {
        // doCall, not call: a failed check must surface as an exception the
        // establish loop can catch, not trigger a nested recover().
        ServerCheckResponse check = doCall(new ServerCheckRequest(), ServerCheckResponse.class);
        if (!check.isSuccess()) {
            throw new JawsServiceException("harbor server check rejected: resultCode="
                    + check.getResultCode());
        }
        String assigned = check.getConnectionId();
        if (assigned == null || assigned.isEmpty()) {
            throw new JawsServiceException("harbor server check returned no connectionId");
        }
        this.connectionId = assigned;
        if (check.isSupportAbilityNegotiation()) {
            // Our own server always answers false; a client pointed at a negotiating
            // node is told plainly instead of silently skipping the negotiation.
            log.warn("[harbor-client] {}:{} requested ability negotiation, which the native"
                    + " client does not implement", host, port);
        }
    }

    /**
     * Where to try next: the address we are on, then the rest of the list walked from
     * the cursor. Walking from a cursor rather than from the top matters — a client
     * that has already failed over once should not all pile back onto the same node.
     */
    private List<String> candidateOrder() {
        List<String> candidates = new ArrayList<>();
        candidates.add(host + ":" + port);
        for (int step = 1; step < targets.size(); step++) {
            int index = (cursor + step) % targets.size();
            String each = targets.get(index);
            if (!candidates.contains(each)) {
                candidates.add(each);
            }
        }
        return candidates;
    }

    /** Random start index over a node list of {@code size}. */
    static int startCursor(int size) {
        return size <= 1 ? 0 : ThreadLocalRandom.current().nextInt(size);
    }

    private void attachTo(String address) {
        String[] parts = address.split(":");
        int known = targets.indexOf(address);
        if (known >= 0) {
            cursor = known;
        }
        attach(parts[0], Integer.parseInt(parts[1]));
    }

    /** Re-open the notification stream over the channel we already hold. */
    private boolean reopenStreamInPlace() {
        try {
            openNotificationStream();
            return true;
        } catch (Exception e) {
            log.warn("[harbor-client] node is reachable but its stream could not be reopened"
                    + " ({}), falling over to another node", e.getMessage());
            return false;
        }
    }

    /** Open a fresh transport to one node; a stale half-dead channel is never reused. */
    private void attach(String newHost, int newPort) {
        WireClient previous = wireClient;
        if (previous != null) {
            try {
                previous.close();
            } catch (Exception e) {
                log.debug("[harbor-client] closing transport away from {}:{}", host, port);
            }
        }
        host = newHost;
        port = newPort;
        WireClient next = new WireClient(buildUrl(config, newHost, newPort));
        wireClient = next;
        if (!next.open()) {
            throw new JawsServiceException("cannot connect to harbor " + newHost + ":" + newPort);
        }
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
            return;
        }
        if (HarborProtocol.typeToken(DynamicConfigChangeRequest.class).equals(type)) {
            // A config broadcast is control traffic, not instance traffic: it does
            // not prove the server is answering naming calls, so it deliberately
            // does not refresh the liveness clock.
            configSink.accept(payload);
            return;
        }
        if (HarborProtocol.typeToken(ConnectResetRequest.class).equals(type)) {
            // Reconnecting means tearing this stream down, so it cannot happen on the
            // stream's own thread; the keep-alive thread has nothing else to do.
            keepAliveScheduler.execute(() -> handleReset(payload));
        }
        // A NotifySubscriberResponse ack is deliberately not sent: harbor's push
        // is latest-state idempotent convergence with no delivery signal, so an
        // ack would only add traffic. The class exists for the frames nacos
        // clients send back.
    }

    /**
     * Comply with a server's order to reconnect. The ack goes out first: it is what
     * tells the expelling node that we left rather than were cut loose — on silence it
     * closes our session itself and we would be told twice.
     */
    private void handleReset(Payload frame) {
        ConnectResetRequest reset = HarborProtocol.parseBody(frame, ConnectResetRequest.class);
        StreamSubject<Object> stream = outbound;
        if (stream != null) {
            stream.onNext(HarborProtocol.encodeResponse(new ConnectResetResponse()));
        }
        if (closed) {
            return;
        }
        String redirectIp = reset.getServerIp();
        try {
            if (redirectIp != null && !redirectIp.isEmpty() && reset.getServerPort() != null) {
                replaceTransport(redirectIp, Integer.parseInt(reset.getServerPort()));
            }
            recover();
        } catch (Exception e) {
            log.warn("[harbor-client] reconnect after connect-reset failed: {}", e.getMessage());
        }
    }

    private void replaceTransport(String newHost, int newPort) {
        attach(newHost, newPort);
        // A named address from the server wins over the rotation until it fails us;
        // one outside our list leaves the cursor where it was.
        int known = targets.indexOf(newHost + ":" + newPort);
        if (known >= 0) {
            cursor = known;
        }
        log.info("[harbor-client] redirected to {}:{}", newHost, newPort);
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

    /**
     * Whether a request can go out right now. A reconcile pass over the redo tables
     * on a dead connection would only queue failures and re-trigger recovery.
     */
    boolean isConnected() {
        CompletableFuture<Void> pending = setupAck;
        return !closed && pending != null && pending.isDone();
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
        log.info("[harbor-client] closing connection connId={}", connectionId);
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

    private static URL buildUrl(HarborClientConfig config, String host, int port) {
        URL url = new URL("wire", host, port, HarborProtocol.RPC_UNARY_SERVICE);
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
