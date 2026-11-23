package org.hongxi.jaws.transport.http2;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.flush.FlushConsolidationHandler;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.AbstractClient;
import org.hongxi.jaws.transport.ChannelState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base class for HTTP/2 based clients, owning everything that is protocol
 * independent: the Netty bootstrap ({@code Http2FrameCodec} +
 * {@code Http2MultiplexHandler}), optional TLS with ALPN, multi-connection
 * support with round-robin selection, lazy reconnection, and lifecycle state.
 * <p>
 * Subclasses implement {@link #request(org.hongxi.jaws.rpc.Request)} with their
 * own request encoding and response handling — this is where the protocol
 * semantics live (jaws payload encoding for {@link Http2Client}, gRPC wire
 * format for the wire module's client). The connection pipeline assembled here is:
 * <pre>
 *   [ssl] → flush_consolidation → http2_codec → http2_multiplex
 * </pre>
 * <p>
 * Speaks plain h2c (HTTP/2 prior-knowledge) by default; TLS is enabled when
 * {@code sslTrustCert} (one-way) or {@code sslCertChain} + {@code sslPrivateKey}
 * (mutual) is configured on the URL. Multi-connection is enabled via the
 * {@code connections} URL parameter to distribute load across backends behind
 * L4 load balancers.
 * <p>
 * Idle-connection liveness relies on HTTP/2 PING frames managed by Netty's
 * {@code Http2FrameCodec} (keepalive) instead of application-level heartbeats.
 *
 * @author shenhongxi
 */
public abstract class AbstractHttp2Client extends AbstractClient {
    private static final Logger log = LoggerFactory.getLogger(AbstractHttp2Client.class);

    /**
     * Shared event loop group for all HTTP/2 client connections.
     * Daemon threads allow JVM to exit even if the group is not explicitly
     * shut down (e.g. in sample applications).
     */
    private static final NioEventLoopGroup nioEventLoopGroup = new NioEventLoopGroup(
            new DefaultThreadFactory("jaws-http2-client", true));

    /** Human-readable client name used in log messages and thread names. */
    private final String clientName;

    private final SslContext sslContext;

    private Bootstrap bootstrap;

    /**
     * Multiple connections for L4 LB distribution. When more than one,
     * requests are distributed across connections via round-robin.
     */
    private volatile io.netty.channel.Channel[] channels;

    /**
     * In-flight dial per connection slot, guarded by this client's monitor.
     * Keeps concurrent reconnect triggers from dialing the same slot twice.
     */
    private ChannelFuture[] dials;

    private final AtomicInteger requestCounter = new AtomicInteger(0);

    protected AbstractHttp2Client(URL url, String clientName) {
        super(url);
        this.clientName = clientName;
        this.sslContext = buildSslContext();
    }

    @Override
    public synchronized boolean open() {
        if (isAvailable()) {
            return true;
        }

        int timeout = url.getIntParameter(UrlParam.Transport.CONNECT_TIMEOUT);
        if (timeout <= 0) {
            throw new JawsFrameworkException(clientName
                    + " init failed: connect timeout must be positive but was " + timeout);
        }

        bootstrap = new Bootstrap()
                .group(nioEventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        // Add TLS if configured (before HTTP/2 codec)
                        if (sslContext != null) {
                            pipeline.addLast("ssl",
                                    sslContext.newHandler(ch.alloc(), url.getHost(), url.getPort()));
                        }
                        // Collapse per-message flushes into one flush per
                        // event-loop turn (teardown paths flush pending writes).
                        pipeline.addLast(Http2PipelineSupport.FLUSH_CONSOLIDATION,
                                new FlushConsolidationHandler(64, true));
                        // HTTP/2 framing & flow control; liveness relies on TCP keepalive
                        // and HTTP/2 PINGs instead of application-level heartbeats
                        pipeline.addLast("http2_codec", configureHttp2Codec(Http2FrameCodecBuilder.forClient()).build());
                        // Optional connection-level handlers (e.g. gRPC keepalive)
                        addOptionalChannelHandlers(pipeline);
                        pipeline.addLast("http2_multiplex", new Http2MultiplexHandler(
                                new io.netty.channel.ChannelInboundHandlerAdapter()));
                    }
                })
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true);

        // Open multiple connections if configured
        int connectionCount = Math.max(1, url.getIntParameter(UrlParam.Transport.CONNECTIONS));
        channels = new io.netty.channel.Channel[connectionCount];
        dials = new ChannelFuture[connectionCount];
        for (int i = 0; i < connectionCount; i++) {
            awaitInitialConnect(i, dial(i), timeout);
        }
        state = ChannelState.ALIVE;

        String tlsInfo = sslContext != null ? "with TLS" : "";
        String connInfo = connectionCount > 1 ? "(" + connectionCount + " connections)" : "";
        log.info("{} opened successfully {} {}: url={}", clientName, tlsInfo, connInfo, url);
        return true;
    }

    /**
     * Start an asynchronous dial for one connection slot, or return the dial
     * already in flight. The listener fills the slot on success, so no caller
     * thread ever waits on the network; {@link #onConnectionEstablished} and
     * {@link #onConnectionFailed} let subclasses track connectivity.
     * <p>
     * Must be called while holding this client's monitor.
     */
    private ChannelFuture dial(int index) {
        ChannelFuture pending = dials[index];
        if (pending != null && !pending.isDone()) {
            return pending;
        }
        ChannelFuture future = bootstrap.connect(url.getHost(), url.getPort());
        dials[index] = future;
        final io.netty.channel.Channel[] slots = channels;
        future.addListener((ChannelFutureListener) f -> {
            boolean accepted = false;
            synchronized (AbstractHttp2Client.this) {
                if (dials != null) {
                    dials[index] = null;
                }
                // A stale dial (client closed or re-opened meanwhile) must not
                // write into the current slot array
                if (f.isSuccess() && slots == channels && !state.isCloseState()) {
                    channels[index] = f.channel();
                    accepted = true;
                }
            }
            if (accepted) {
                log.info("{} connected connection[{}]: url={}", clientName, index, url.getUri());
                onConnectionEstablished(index);
            } else {
                if (f.channel().isOpen()) {
                    f.channel().close();
                }
                log.warn("{} connect failed for connection[{}]: url={}",
                        clientName, index, url.getUri(), f.cause());
                onConnectionFailed(index, f.cause());
            }
        });
        return future;
    }

    /**
     * Startup-only bounded wait for one dial; request paths ride the dial
     * future asynchronously instead ({@link #activeChannelAsync()}). The
     * error surface matches the historical synchronous connect: a named
     * {@link JawsServiceException} distinguishing failure from timeout.
     */
    private void awaitInitialConnect(int index, ChannelFuture channelFuture, int timeout) {
        long start = System.currentTimeMillis();
        boolean completed = channelFuture.awaitUninterruptibly(timeout, TimeUnit.MILLISECONDS);
        boolean success = channelFuture.isSuccess();
        if (completed && success) {
            // The dial listener fills the slot too, but it may not have run
            // yet on the event loop — settle it here so open() returns with
            // every slot usable
            settleDial(index, channelFuture);
            return;
        }
        if (!completed) {
            channelFuture.cancel(false);
        }
        // Close the channel if it was opened but connect failed or timed out
        if (channelFuture.channel().isOpen()) {
            channelFuture.channel().close();
        }
        if (completed && channelFuture.cause() != null) {
            throw new JawsServiceException(clientName
                    + " failed to connect to server, url: " + url.getUri()
                    + ", completed: " + completed + ", success: " + success,
                    channelFuture.cause());
        }
        throw new JawsServiceException(clientName
                + " connect to server timeout, url: " + url.getUri()
                + ", cost: " + (System.currentTimeMillis() - start)
                + "ms, completed: " + completed + ", success: " + success);
    }

    /**
     * Fill a slot whose dial succeeded but whose listener has not run yet.
     * Only ever writes an empty slot of the current array, so it cannot race
     * a concurrent close or re-open into resurrecting a dropped connection.
     */
    private synchronized void settleDial(int index, ChannelFuture f) {
        if (f != null && f.isSuccess() && channels != null && index < channels.length
                && channels[index] == null && !state.isCloseState() && f.channel().isActive()) {
            channels[index] = f.channel();
        }
    }

    /**
     * Hook invoked when an asynchronous dial establishes connection slot
     * {@code index}, on the connection's event loop. Default no-op;
     * subclasses may drive connectivity state here. Must not block.
     *
     * @param index the connection slot that became active
     */
    protected void onConnectionEstablished(int index) {
        // no-op by default
    }

    /**
     * Hook invoked when an asynchronous dial for slot {@code index} fails or
     * is cancelled by close, on the connection's event loop. Default no-op.
     * Must not block.
     *
     * @param index the connection slot whose dial failed
     * @param cause the dial failure, or {@code null} when cancelled
     */
    protected void onConnectionFailed(int index, Throwable cause) {
        // no-op by default
    }

    /**
     * Hook for optional connection-level handlers, invoked between
     * {@code http2_codec} and {@code http2_multiplex}. Default no-op;
     * subclasses may install e.g. a gRPC keepalive PING sender.
     *
     * @param pipeline the connection channel pipeline
     */
    protected void addOptionalChannelHandlers(ChannelPipeline pipeline) {
        // no-op by default
    }

    /**
     * Hook to customize the HTTP/2 frame codec builder before it is built.
     * Subclasses may override to set parameters such as
     * {@code maxHeaderListSize} (inbound metadata size limit).
     *
     * @param builder the codec builder with default settings
     * @return the customized builder (may be the same instance)
     */
    protected Http2FrameCodecBuilder configureHttp2Codec(Http2FrameCodecBuilder builder) {
        // Advertise an 8 MiB stream window (aligned with Dubbo TripleConfig):
        // the protocol default 64 KiB throttles bulk streams on non-trivial RTT
        return builder.initialSettings(new io.netty.handler.codec.http2.Http2Settings()
                .initialWindowSize(Http2Constants.INITIAL_WINDOW_SIZE));
    }

    /**
     * @return the SSL context, or null if TLS is not configured
     */
    protected SslContext getSslContext() {
        return sslContext;
    }

    /**
     * Build an {@link SslContext} for TLS if trust cert or mutual TLS is configured.
     * Uses ALPN to negotiate HTTP/2.
     *
     * @return the SslContext, or null if TLS is not configured
     */
    private SslContext buildSslContext() {
        String trustCert = url.getParameter(UrlParam.Transport.SSL_TRUST_CERT);
        String certChain = url.getParameter(UrlParam.Transport.SSL_CERT_CHAIN);
        String privateKey = url.getParameter(UrlParam.Transport.SSL_PRIVATE_KEY);

        boolean hasTrust = trustCert != null && !trustCert.isEmpty();
        boolean hasMutualTls = certChain != null && !certChain.isEmpty()
                && privateKey != null && !privateKey.isEmpty();

        if (!hasTrust && !hasMutualTls) {
            return null;
        }

        try {
            SslContextBuilder builder;
            if (hasMutualTls) {
                builder = SslContextBuilder.forClient()
                        .keyManager(new File(certChain), new File(privateKey));
            } else {
                builder = SslContextBuilder.forClient();
            }
            if (hasTrust) {
                builder.trustManager(new File(trustCert));
            }
            return builder
                    .sslProvider(SslProvider.JDK)
                    .applicationProtocolConfig(new ApplicationProtocolConfig(
                            ApplicationProtocolConfig.Protocol.ALPN,
                            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                            ApplicationProtocolNames.HTTP_2))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build client SSL context", e);
        }
    }

    /**
     * Return an active channel, lazily reconnecting if the selected
     * channel has gone away. The reconnect dial itself is asynchronous, but
     * this method waits for it (bounded by the connect timeout) so that
     * synchronous callers — the jaws-protocol {@link Http2Client} — keep
     * their self-healing semantics. Fully asynchronous callers use
     * {@link #activeChannelAsync()} instead and never block a thread.
     */
    protected io.netty.channel.Channel activeChannel() {
        io.netty.channel.Channel channel = selectChannel();
        if (channel == null || !channel.isActive()) {
            int timeout = url.getIntParameter(UrlParam.Transport.CONNECT_TIMEOUT);
            ChannelFuture[] pending;
            synchronized (this) {
                reconnect();
                pending = dials != null ? dials.clone() : null;
            }
            if (pending != null) {
                for (int i = 0; i < pending.length; i++) {
                    ChannelFuture f = pending[i];
                    if (f != null && !f.isDone()) {
                        f.awaitUninterruptibly(timeout, TimeUnit.MILLISECONDS);
                    }
                    settleDial(i, f);
                }
            }
            channel = selectChannel();
        }
        if (channel == null || !channel.isActive()) {
            throw new JawsServiceException(clientName + " channel is not active: url=" + url.getUri());
        }
        return channel;
    }

    /**
     * Asynchronous variant of {@link #activeChannel()}: completes with the
     * selected channel, starting an asynchronous dial when its slot is down
     * and riding the dial future. No calling thread blocks on the network;
     * the returned future fails with a named {@link JawsServiceException}
     * when the dial fails or the client has no usable connections.
     *
     * @return a future completing with an active connection channel
     */
    protected CompletableFuture<io.netty.channel.Channel> activeChannelAsync() {
        io.netty.channel.Channel[] slots = channels;
        if (slots == null || slots.length == 0 || state.isCloseState()) {
            return CompletableFuture.failedFuture(new JawsServiceException(
                    "No HTTP/2 connections available: url=" + url.getUri()));
        }
        int index = slots.length == 1 ? 0
                : Math.abs(requestCounter.getAndIncrement() % slots.length);
        io.netty.channel.Channel channel = slots[index];
        if (channel != null && channel.isActive()) {
            return CompletableFuture.completedFuture(channel);
        }
        final ChannelFuture dialFuture;
        synchronized (this) {
            if (state.isCloseState() || channels != slots) {
                return CompletableFuture.failedFuture(new JawsServiceException(
                        "No HTTP/2 connections available: url=" + url.getUri()));
            }
            channel = channels[index];
            if (channel != null && channel.isActive()) {
                return CompletableFuture.completedFuture(channel);
            }
            if (channel != null) {
                channel.close();
                channels[index] = null;
            }
            dialFuture = dial(index);
        }
        CompletableFuture<io.netty.channel.Channel> result = new CompletableFuture<>();
        dialFuture.addListener((ChannelFutureListener) f -> {
            if (f.isSuccess() && f.channel().isActive()) {
                result.complete(f.channel());
            } else {
                result.completeExceptionally(new JawsServiceException(
                        clientName + " channel is not available: url=" + url.getUri(), f.cause()));
            }
        });
        return result;
    }

    /**
     * Select a channel using round-robin for multi-connection setups.
     *
     * @return the selected channel, or {@code null} when the slot was vacated —
     *         closed, or left empty by a failed reconnect. Callers decide what
     *         that means: retry (reconnect) or fail by name.
     */
    private io.netty.channel.Channel selectChannel() {
        if (channels == null || channels.length == 0) {
            throw new JawsServiceException("No HTTP/2 connections available: url=" + url.getUri());
        }
        if (channels.length == 1) {
            return channels[0];
        }
        int idx = Math.abs(requestCounter.getAndIncrement() % channels.length);
        return channels[idx];
    }

    /**
     * Re-establish dead connections by starting asynchronous dials. Returns
     * immediately — no caller thread (least of all an event loop) waits on
     * the network; slots are filled by the dial listeners. Called lazily
     * from request paths when a multiplexed connection has gone away, or
     * proactively when a GOAWAY frame is received from the server.
     */
    protected synchronized void reconnect() {
        if (state.isCloseState() || channels == null) {
            return;
        }
        for (int i = 0; i < channels.length; i++) {
            io.netty.channel.Channel ch = channels[i];
            if (ch != null && ch.isActive()) {
                continue;
            }
            if (ch != null) {
                ch.close();
                channels[i] = null;
            }
            dial(i);
        }
    }

    @Override
    protected void doClose() {
        synchronized (this) {
            // Cancel in-flight dials so a closed client cannot resurrect its
            // connections behind its owner's back; the dial listeners see the
            // cancellation and drop their channels
            if (dials != null) {
                for (ChannelFuture f : dials) {
                    if (f != null && !f.isDone()) {
                        f.cancel(false);
                    }
                }
                dials = null;
            }
            if (channels != null) {
                for (io.netty.channel.Channel ch : channels) {
                    if (ch != null) {
                        ch.close();
                    }
                }
                channels = null;
            }
        }
    }

    @Override
    public boolean isAvailable() {
        if (!super.isAvailable()) {
            return false;
        }
        if (channels == null) {
            return false;
        }
        // At least one connection must be open
        for (io.netty.channel.Channel ch : channels) {
            if (ch != null && ch.isOpen()) {
                return true;
            }
        }
        return false;
    }
}
