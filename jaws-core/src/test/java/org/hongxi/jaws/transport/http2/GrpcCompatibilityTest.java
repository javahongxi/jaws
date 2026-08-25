package org.hongxi.jaws.transport.http2;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import org.hongxi.jaws.rpc.DefaultProvider;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.ProviderMessageHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for gRPC → Jaws compatibility: verifies that a standard gRPC client
 * (simulated via raw HTTP/2 frames with gRPC content-type and framing) can
 * successfully invoke Jaws services through the HTTP/2 transport.
 *
 * @author shenhongxi
 */
class GrpcCompatibilityTest {

    private static final String ECHO_INTERFACE = EchoService.class.getName();

    private Http2Server server;
    private EventLoopGroup clientGroup;
    private Channel clientChannel;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        port = findFreePort();
        URL url = new URL("jaws", "127.0.0.1", port, EchoService.class.getName());
        url.addParameter("serialization", "hessian2");

        EchoServiceImpl echoImpl = new EchoServiceImpl();
        DefaultProvider<EchoService> provider = new DefaultProvider<>(EchoService.class, url, echoImpl);
        ProviderMessageHandler handler = new ProviderMessageHandler();
        handler.addProvider(provider);

        server = new Http2Server(url, handler);
        assertTrue(server.open());

        // Set up raw HTTP/2 client
        clientGroup = new NioEventLoopGroup(1);
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(clientGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast("http2_codec", Http2FrameCodecBuilder.forClient().build());
                        ch.pipeline().addLast("http2_multiplex", new Http2MultiplexHandler(
                                new io.netty.channel.ChannelInboundHandlerAdapter()));
                    }
                });
        clientChannel = bootstrap.connect("127.0.0.1", port).syncUninterruptibly().channel();
    }

    @AfterEach
    void tearDown() {
        if (clientChannel != null) {
            clientChannel.close().syncUninterruptibly();
        }
        if (clientGroup != null) {
            clientGroup.shutdownGracefully();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void grpcJsonEchoCall() throws Exception {
        GrpcStreamCollector collector = sendGrpcRequest("/" + ECHO_INTERFACE + "/echo",
                "{\"message\":\"hello grpc\"}");

        GrpcResponse response = collector.get(10, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals("200", response.status);
        assertEquals("0", response.trailers.get("grpc-status"), "grpc-status should be OK (0)");

        // Decode the gRPC response frame
        String responseJson = new String(response.dataBytes, StandardCharsets.UTF_8);
        assertEquals("hello grpc", responseJson.replace("\"", ""));
    }

    @Test
    void grpcJsonAddCall() throws Exception {
        GrpcStreamCollector collector = sendGrpcRequest("/" + ECHO_INTERFACE + "/add",
                "{\"a\":3,\"b\":5}");

        GrpcResponse response = collector.get(10, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals("200", response.status);
        assertEquals("0", response.trailers.get("grpc-status"));

        String responseJson = new String(response.dataBytes, StandardCharsets.UTF_8);
        // The add method returns int 8, which should be serialized as JSON
        assertEquals("8", responseJson);
    }

    @Test
    void grpcMethodNotFound() throws Exception {
        GrpcStreamCollector collector = sendGrpcRequest("/" + ECHO_INTERFACE + "/nonExistent",
                "{\"message\":\"test\"}");

        GrpcResponse response = collector.get(10, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals("200", response.status);
        // Should have UNIMPLEMENTED status (12)
        assertEquals("12", response.trailers.get("grpc-status"));
    }

    @Test
    void grpcServiceNotFound() throws Exception {
        GrpcStreamCollector collector = sendGrpcRequest("/com.unknown.UnknownService/method",
                "{}");

        GrpcResponse response = collector.get(10, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals("200", response.status);
        assertEquals("12", response.trailers.get("grpc-status"));
    }

    // ==================== Helper methods ====================

    private GrpcStreamCollector sendGrpcRequest(String path, String jsonBody) throws Exception {
        Http2StreamChannel streamChannel = new Http2StreamChannelBootstrap(clientChannel)
                .handler(new GrpcStreamCollector())
                .open().syncUninterruptibly().getNow();

        // Build gRPC headers
        Http2Headers headers = new DefaultHttp2Headers()
                .method("POST")
                .scheme("http")
                .path(path)
                .authority("127.0.0.1:" + port)
                .set("content-type", "application/grpc+json")
                .set("te", "trailers");
        streamChannel.write(new DefaultHttp2HeadersFrame(headers));

        // Build gRPC framed body: 5-byte prefix + JSON
        byte[] jsonBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        byte[] grpcFrame = GrpcCodec.encodeFrame(jsonBytes);
        streamChannel.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(grpcFrame), true));

        return streamChannel.pipeline().get(GrpcStreamCollector.class);
    }

    /**
     * Collects gRPC response components: HEADERS, DATA frames, and TRAILERS.
     */
    static class GrpcStreamCollector extends SimpleChannelInboundHandler<Object> {
        String status;
        final List<ByteBuf> dataBuffers = new ArrayList<>();
        final Map<String, String> trailers = new LinkedHashMap<>();
        boolean endStreamReceived = false;
        final CompletableFuture<GrpcResponse> future = new CompletableFuture<>();
        boolean headersReceived = false;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Http2HeadersFrame headersFrame) {
                if (!headersReceived) {
                    // First HEADERS frame: response headers
                    status = headersFrame.headers().status() != null
                            ? headersFrame.headers().status().toString() : null;
                    headersReceived = true;
                } else {
                    // Second HEADERS frame: trailers
                    headersFrame.headers().forEach(entry ->
                            trailers.put(entry.getKey().toString(), entry.getValue().toString()));
                }
                if (headersFrame.isEndStream()) {
                    completeFuture();
                }
            } else if (msg instanceof Http2DataFrame dataFrame) {
                if (dataFrame.content().isReadable()) {
                    dataBuffers.add(dataFrame.content().retain());
                }
                if (dataFrame.isEndStream()) {
                    completeFuture();
                }
            }
        }

        private void completeFuture() {
            if (!endStreamReceived) {
                endStreamReceived = true;
                // Merge all data buffers
                byte[] allData = new byte[0];
                for (ByteBuf buf : dataBuffers) {
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.readBytes(bytes);
                    buf.release();
                    byte[] merged = new byte[allData.length + bytes.length];
                    System.arraycopy(allData, 0, merged, 0, allData.length);
                    System.arraycopy(bytes, 0, merged, allData.length, bytes.length);
                    allData = merged;
                }
                // Strip gRPC 5-byte prefix to get the message body
                byte[] messageBytes = allData.length >= 5 ? GrpcCodec.decodeFrame(allData) : allData;
                future.complete(new GrpcResponse(status, messageBytes, trailers));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            future.completeExceptionally(cause);
        }

        public GrpcResponse get(long timeout, TimeUnit unit) throws Exception {
            return future.get(timeout, unit);
        }
    }

    static class GrpcResponse {
        final String status;
        final byte[] dataBytes;
        final Map<String, String> trailers;

        GrpcResponse(String status, byte[] dataBytes, Map<String, String> trailers) {
            this.status = status;
            this.dataBytes = dataBytes;
            this.trailers = trailers;
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // ==================== Test service ====================

    public interface EchoService {
        String echo(String message);

        int add(int a, int b);
    }

    public static class EchoServiceImpl implements EchoService {
        @Override
        public String echo(String message) {
            return message;
        }

        @Override
        public int add(int a, int b) {
            return a + b;
        }
    }
}
