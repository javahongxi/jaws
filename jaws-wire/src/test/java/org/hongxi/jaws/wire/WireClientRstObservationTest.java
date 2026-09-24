package org.hongxi.jaws.wire;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.wire.health.HealthCheckResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end proof that an inbound RST_STREAM reaches the wire client's stream
 * handler and fails the pending call immediately with the mapped grpc-style
 * status, instead of burning the whole request timeout. The peer is a minimal
 * raw-netty h2c server that resets every stream with CANCEL — the shape a
 * grpc-java server produces on server-side cancellation, and the scenario the
 * previous implementation was blind to (the call waited out its timeout).
 *
 * @author shenhongxi
 */
class WireClientRstObservationTest {

    private static final String SERVICE = "org.hongxi.jaws.wire.RstProbeService";

    private NioEventLoopGroup group;
    private Channel serverChannel;
    private WireClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (group != null) {
            group.shutdownGracefully(0, 200, TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void peerResetFailsTheCallImmediatelyWithCanceledStatus() throws Exception {
        int port = freePort();
        startResettingServer(port);

        client = new WireClient(new URL("wire", "127.0.0.1", port, SERVICE));
        assertTrue(client.open(), "client should connect");

        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName(SERVICE);
        request.setMethodName("SayHello");
        request.setRequestId(1L);
        request.setArguments(new Object[]{
                org.hongxi.jaws.wire.health.HealthCheckRequest.newBuilder()
                        .setService("probe").build()});

        long start = System.nanoTime();
        Response response = client.request(request, HealthCheckResponse.parser(),
                WireCallOptions.DEFAULT.withDeadlineMs(30_000));
        try {
            response.getValue();
            fail("the call must fail on peer reset");
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 10_000,
                    "peer reset must fail the call fast, took " + elapsedMs + "ms");
            assertTrue(String.valueOf(e).contains("reset"),
                    "failure must name the reset, got: " + e);
        }
    }

    /**
     * A minimal h2c server whose every stream is answered with an immediate
     * RST_STREAM(CANCEL) — no trailers, no DATA, connection stays alive.
     */
    private void startResettingServer(int port) throws InterruptedException {
        group = new NioEventLoopGroup(2);
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                Http2FrameCodecBuilder.forServer().build(),
                                new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                                    @Override
                                    protected void initChannel(Channel stream) {
                                        // Every stream is answered with an immediate
                                        // RST_STREAM(CANCEL) — no trailers, no DATA,
                                        // connection stays alive
                                        stream.pipeline().addLast(
                                                new ChannelInboundHandlerAdapter() {
                                                    @Override
                                                    public void channelActive(ChannelHandlerContext ctx) {
                                                        ctx.writeAndFlush(new DefaultHttp2ResetFrame(
                                                                Http2Error.CANCEL));
                                                    }
                                                });
                                    }
                                }));
                    }
                });
        serverChannel = bootstrap.bind(port).sync().channel();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
