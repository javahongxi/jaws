package org.hongxi.jaws.transport.http2;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.util.RpcUtils;
import org.hongxi.jaws.exception.JawsAbstractException;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.DefaultResponseFuture;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.ResponseFuture;
import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.serialization.Serialization;
import org.hongxi.jaws.transport.AbstractClient;
import org.hongxi.jaws.transport.ChannelState;
import org.hongxi.jaws.transport.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP/2-based {@link Client} implementation maintaining multiplexed
 * h2c (or h2 over TLS) connections per remote URL. Each request opens its own
 * HTTP/2 stream via {@link Http2StreamChannelBootstrap}, so concurrent requests
 * never contend on application-level framing — the head-of-line blocking present
 * in the request-id multiplexed jaws TCP protocol is eliminated by design.
 * <p>
 * Like {@code NettyClient}, async requests register a {@link ResponseFuture}
 * callback guarded by a one-shot timeout on a shared HashedWheelTimer; the
 * per-stream {@link Http2StreamResponseHandler} completes the future when the
 * response END_STREAM arrives, or fails it on stream reset/close.
 * <p>
 * Idle-connection liveness relies on HTTP/2 PING frames managed by Netty's
 * {@code Http2FrameCodec} (keepalive) instead of application-level heartbeats.
 * <p>
 * Gateway-friendly enhancements:
 * <ul>
 *   <li>Mirrors key metadata (interface, method, paramDesc, group, version) into
 *       HTTP/2 HEADERS for gateway-level routing and observability</li>
 *   <li>Optional TLS with ALPN when {@code sslTrustCert} (or mutual TLS cert/key)
 *       is configured</li>
 *   <li>Multi-connection support via {@code connections} parameter to distribute
 *       load across backends behind L4 load balancers</li>
 * </ul>
 *
 * @author shenhongxi
 */
public class Http2Client extends AbstractClient {
    private static final Logger log = LoggerFactory.getLogger(Http2Client.class);

    private static final NioEventLoopGroup nioEventLoopGroup = new NioEventLoopGroup();

    private final Serialization serialization;
    private final int connectionCount;
    private final SslContext sslContext;

    private Bootstrap bootstrap;
    /**
     * Multiple connections for L4 LB distribution. When connectionCount > 1,
     * requests are distributed across connections via round-robin.
     */
    private volatile io.netty.channel.Channel[] channels;
    private final AtomicInteger requestCounter = new AtomicInteger(0);

    public Http2Client(URL url) {
        super(url);
        this.serialization = Http2PayloadCodec.resolveSerialization(
                url.getParameter(UrlParam.Transport.SERIALIZATION));
        this.connectionCount = Math.max(1, url.getIntParameter(UrlParam.Transport.CONNECTIONS));
        this.sslContext = buildSslContext();
    }

    public Serialization getSerialization() {
        return serialization;
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

    @Override
    public Response request(Request request) {
        if (!isAvailable()) {
            throw new JawsServiceException("HTTP/2 channel is not available: url="
                    + url.getUri() + RpcUtils.toString(request));
        }

        boolean async = false;
        Object asyncFlag = RpcContext.getContext().getAttribute(JawsConstants.ASYNC_FLAG);
        if (asyncFlag instanceof Boolean b) {
            async = b;
        }

        int timeout = url.getMethodParameter(
                request.getMethodName(), request.getParamDesc(),
                UrlParam.Transport.REQUEST_TIMEOUT.getName(), UrlParam.Transport.REQUEST_TIMEOUT.intValue());

        DefaultResponseFuture responseFuture = new DefaultResponseFuture(request, timeout, url);

        try {
            io.netty.channel.Channel connChannel = selectConnection();
            if (!connChannel.isActive()) {
                reconnect();
                connChannel = selectConnection();
            }
            if (!connChannel.isActive()) {
                throw new JawsServiceException("HTTP/2 channel is not active: url="
                        + url.getUri() + RpcUtils.toString(request));
            }

            io.netty.channel.Channel streamChannel =
                    new Http2StreamChannelBootstrap(connChannel)
                            .handler(new Http2StreamResponseHandler(this, request.getRequestId()))
                            .open().syncUninterruptibly().getNow();

            // Register before writing so a fast failure (channelInactive) can
            // always find and fail the future
            registerCallback(request.getRequestId(), responseFuture);

            byte[] payload = Http2PayloadCodec.encodeRequest(request, serialization);
            Http2Headers headers = buildRequestHeaders(request);
            streamChannel.write(new DefaultHttp2HeadersFrame(headers));
            streamChannel.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(payload), true))
                    .addListener(f -> {
                        if (!f.isSuccess()) {
                            ResponseFuture future = removeCallback(request.getRequestId());
                            if (future != null) {
                                DefaultResponse errorResponse = new DefaultResponse(request.getRequestId());
                                errorResponse.setException(new JawsServiceException(
                                        "HTTP/2 stream write failed", f.cause()));
                                future.onFailure(errorResponse);
                            }
                        }
                    });
        } catch (Exception e) {
            // write path failed before/as the callback was registered
            ResponseFuture future = removeCallback(request.getRequestId());
            if (future != null) {
                DefaultResponse errorResponse = new DefaultResponse(request.getRequestId());
                errorResponse.setException(new JawsServiceException("HTTP/2 request error", e));
                future.onFailure(errorResponse);
            }
            log.error("HTTP/2 request failed: url={} {}, {}", url.getUri(),
                    RpcUtils.toString(request), e.getMessage());
            if (e instanceof JawsAbstractException jae) {
                throw jae;
            }
            throw new JawsServiceException("Http2Client request failed: url="
                    + url.getUri() + " " + RpcUtils.toString(request), e);
        }

        return async ? responseFuture : new DefaultResponse(responseFuture);
    }

    /**
     * Build HTTP/2 HEADERS for a request, including mirrored metadata for
     * gateway visibility.
     */
    private Http2Headers buildRequestHeaders(Request request) {
        Http2Headers headers = new DefaultHttp2Headers()
                .method("POST")
                .scheme(sslContext != null ? "https" : "http")
                .path(Http2Constants.PATH)
                .authority(url.getHostPort())
                .set(Http2Constants.HEADER_CONTENT_TYPE, Http2Constants.CONTENT_TYPE)
                .set(Http2Constants.HEADER_SERIALIZATION,
                        url.getParameter(UrlParam.Transport.SERIALIZATION));

        // Mirror metadata for gateway-level routing and observability
        if (request.getInterfaceName() != null) {
            headers.set(Http2Constants.HEADER_INTERFACE, request.getInterfaceName());
        }
        if (request.getMethodName() != null) {
            headers.set(Http2Constants.HEADER_METHOD, request.getMethodName());
        }
        if (request.getParamDesc() != null) {
            headers.set(Http2Constants.HEADER_PARAM_DESC, request.getParamDesc());
        }

        // Mirror group and version from URL parameters if available
        String group = url.getParameter(UrlParam.Identity.GROUP);
        if (group != null && !group.isEmpty()) {
            headers.set(Http2Constants.HEADER_GROUP, group);
        }
        String version = url.getParameter(UrlParam.Identity.VERSION);
        if (version != null && !version.isEmpty()) {
            headers.set(Http2Constants.HEADER_VERSION, version);
        }

        return headers;
    }

    /**
     * Select a connection channel using round-robin for multi-connection setups.
     */
    private io.netty.channel.Channel selectConnection() {
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
     * Open a server-streaming request: send one request and receive a
     * {@link Flow.Publisher} that emits each response item as it arrives.
     * <p>
     * The caller subscribes to the returned publisher to consume the stream.
     *
     * @param request the RPC request
     * @return a publisher emitting streamed response items
     */
    public Flow.Publisher<Object> requestStream(Request request) {
        if (!isAvailable()) {
            throw new JawsServiceException("HTTP/2 channel is not available: url="
                    + url.getUri() + RpcUtils.toString(request));
        }

        try {
            io.netty.channel.Channel connChannel = selectConnection();
            if (!connChannel.isActive()) {
                reconnect();
                connChannel = selectConnection();
            }
            if (!connChannel.isActive()) {
                throw new JawsServiceException("HTTP/2 channel is not active: url="
                        + url.getUri() + RpcUtils.toString(request));
            }

            // Create the streaming handler which doubles as a Flow.Publisher
            Http2StreamClientHandler streamHandler = new Http2StreamClientHandler(serialization);

            io.netty.channel.Channel streamChannel =
                    new Http2StreamChannelBootstrap(connChannel)
                            .handler(streamHandler)
                            .open().syncUninterruptibly().getNow();

            // Send request headers with streaming mode
            byte[] payload = Http2PayloadCodec.encodeRequest(request, serialization);
            Http2Headers headers = buildRequestHeaders(request)
                    .set(Http2Constants.HEADER_STREAMING, StreamType.SERVER.getWireValue());
            streamChannel.write(new DefaultHttp2HeadersFrame(headers));
            streamChannel.writeAndFlush(new DefaultHttp2DataFrame(
                    Unpooled.wrappedBuffer(payload), true)).addListener(f -> {
                if (!f.isSuccess()) {
                    log.error("HTTP/2 stream write failed for streaming request", f.cause());
                    streamChannel.close();
                }
            });

            return streamHandler;
        } catch (Exception e) {
            log.error("HTTP/2 streaming request failed: url={} {}, {}", url.getUri(),
                    RpcUtils.toString(request), e.getMessage());
            if (e instanceof JawsAbstractException jae) {
                throw jae;
            }
            throw new JawsServiceException("Http2Client streaming request failed: url="
                    + url.getUri() + " " + RpcUtils.toString(request), e);
        }
    }



    @Override
    public synchronized boolean open() {
        if (isAvailable()) {
            return true;
        }

        int timeout = url.getIntParameter(UrlParam.Transport.CONNECT_TIMEOUT);
        if (timeout <= 0) {
            throw new JawsFrameworkException("Http2Client init failed: connect timeout must be positive but was " + timeout);
        }

        bootstrap = new Bootstrap();
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout);
        bootstrap.option(ChannelOption.TCP_NODELAY, true);
        bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        bootstrap.group(nioEventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        // Add TLS if configured (before HTTP/2 codec)
                        if (sslContext != null) {
                            pipeline.addLast("ssl", sslContext.newHandler(ch.alloc(),
                                    url.getHost(), url.getPort()));
                        }
                        // HTTP/2 framing & flow control; liveness relies on TCP keepalive
                        // and HTTP/2 PINGs instead of application-level heartbeats
                        pipeline.addLast("http2_codec", Http2FrameCodecBuilder.forClient().build());
                        pipeline.addLast("http2_multiplex", new Http2MultiplexHandler(
                                new io.netty.channel.ChannelInboundHandlerAdapter()));
                    }
                });

        // Open multiple connections if configured
        channels = new io.netty.channel.Channel[connectionCount];
        for (int i = 0; i < connectionCount; i++) {
            doConnect(i);
        }
        state = ChannelState.ALIVE;

        String tlsInfo = sslContext != null ? " with TLS" : "";
        String connInfo = connectionCount > 1 ? " (" + connectionCount + " connections)" : "";
        log.info("Http2Client opened successfully{}{}: url={}", tlsInfo, connInfo, url);
        return true;
    }

    private void doConnect(int index) {
        ChannelFuture future = bootstrap.connect(url.getHost(), url.getPort()).syncUninterruptibly();
        if (!future.isSuccess()) {
            throw new JawsServiceException("Http2Client connect failed: url=" + url.getUri(), future.cause());
        }
        channels[index] = future.channel();
    }

    /**
     * Re-establish all connections. Called lazily from {@link #request(Request)}
     * when the multiplexed connection has gone away.
     */
    synchronized void reconnect() {
        if (state.isCloseState()) {
            return;
        }
        if (channels != null) {
            boolean allActive = true;
            for (io.netty.channel.Channel ch : channels) {
                if (ch == null || !ch.isActive()) {
                    allActive = false;
                    break;
                }
            }
            if (allActive) {
                return;
            }
        }
        // Close inactive channels and reconnect
        if (channels != null) {
            for (int i = 0; i < channels.length; i++) {
                if (channels[i] != null && !channels[i].isActive()) {
                    channels[i].close();
                    channels[i] = null;
                }
                if (channels[i] == null) {
                    try {
                        doConnect(i);
                        log.info("Http2Client reconnected connection[{}]: url={}", i, url.getUri());
                    } catch (Exception e) {
                        log.error("Http2Client reconnect failed for connection[{}]: url={}",
                                i, url.getUri(), e);
                    }
                }
            }
        }
    }

    @Override
    protected void doClose() {
        if (channels != null) {
            for (io.netty.channel.Channel ch : channels) {
                if (ch != null) {
                    ch.close();
                }
            }
            channels = null;
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
