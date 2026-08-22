package org.hongxi.jaws.transport.grpc;

import io.grpc.ServerBuilder;
import org.hongxi.jaws.common.ChannelState;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.serialization.Serialization;
import org.hongxi.jaws.transport.Channel;
import org.hongxi.jaws.transport.MessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * gRPC-based {@link org.hongxi.jaws.transport.Server} implementation.
 * <p>
 * Starts a gRPC server that dispatches incoming requests to the Jaws
 * {@link MessageHandler} pipeline via {@link GrpcServerService}.
 * <p>
 * Unlike {@code NettyServer}, this server does not use the Jaws binary
 * protocol or {@link org.hongxi.jaws.codec.Codec}. gRPC/HTTP2 handles
 * all framing; business payloads are serialized via Jaws
 * {@link Serialization} directly.
 *
 * @author shenhongxi
 */
public class GrpcServer implements org.hongxi.jaws.transport.Server {
    private static final Logger log = LoggerFactory.getLogger(GrpcServer.class);

    private final URL url;
    private final MessageHandler messageHandler;
    private final Serialization serialization;

    private io.grpc.Server grpcServer;
    private volatile ChannelState state = ChannelState.UNINIT;
    private final AtomicInteger activeRequests = new AtomicInteger(0);

    /** Lightweight Channel facade passed to MessageHandler for server-side context. */
    private final Channel serverChannel = new GrpcServerChannel();

    public GrpcServer(URL url, MessageHandler messageHandler) {
        this.url = url;
        this.messageHandler = messageHandler;
        this.serialization = GrpcPayloadCodec.resolveSerialization(url);
    }

    @Override
    public boolean open() {
        if (isAvailable()) {
            log.debug("gRPC server already open, url={}", url);
            return true;
        }

        int port = url.getPort();
        GrpcServerService service = new GrpcServerService(messageHandler, serverChannel, serialization);

        try {
            grpcServer = ServerBuilder.forPort(port)
                    .addService(service)
                    .build()
                    .start();
            state = ChannelState.ALIVE;
            log.info("gRPC server started on port {}: url={}", port, url);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start gRPC server on port " + port, e);
        }

        return true;
    }

    @Override
    public synchronized void close() {
        close(0);
    }

    @Override
    public synchronized void close(int timeout) {
        if (state.isCloseState()) return;

        try {
            if (grpcServer != null) {
                grpcServer.shutdown();
                int waitMs = timeout > 0 ? timeout :
                        url.getIntParameter(URLParamType.gracefulShutdownTimeout);
                if (waitMs > 0) {
                    grpcServer.awaitTermination(waitMs, TimeUnit.MILLISECONDS);
                }
                if (!grpcServer.isTerminated()) {
                    grpcServer.shutdownNow();
                }
                grpcServer = null;
            }
            state = ChannelState.CLOSE;
            log.info("gRPC server closed: url={}", url);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (grpcServer != null) {
                grpcServer.shutdownNow();
            }
            state = ChannelState.CLOSE;
        }
    }

    @Override
    public boolean isAvailable() {
        return state.isAliveState();
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public void stopAccept() {
        if (grpcServer != null) {
            // gRPC doesn't have a direct "stop accepting" API;
            // shutdown stops accepting new calls while allowing in-flight to complete
            grpcServer.shutdown();
            log.info("gRPC server stopAccept: no longer accepting new calls, url={}", url);
        }
    }

    @Override
    public void awaitInactiveRequests(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (activeRequests.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public AtomicInteger getActiveRequests() {
        return activeRequests;
    }

    /**
     * Minimal Channel implementation for the gRPC server side.
     * Provides URL context to the MessageHandler without a real connection lifecycle.
     */
    private class GrpcServerChannel implements Channel {
        @Override
        public boolean open() {
            return true;
        }

        @Override
        public void close() {
        }

        @Override
        public void close(int timeout) {
        }

        @Override
        public boolean isAvailable() {
            return GrpcServer.this.isAvailable();
        }

        @Override
        public URL getUrl() {
            return url;
        }
    }
}
