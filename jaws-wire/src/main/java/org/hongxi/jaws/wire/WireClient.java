package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
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
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.exception.JawsAbstractException;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.AbstractClient;
import org.hongxi.jaws.transport.ChannelState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * gRPC client implementation based on Netty HTTP/2, independent of jaws-core's
 * {@code Http2Client}. Maintains a multiplexed h2c connection to the remote
 * server; each request opens its own HTTP/2 stream.
 * <p>
 * The request argument must be a protobuf {@link Message} (the first element
 * of {@link Request#getArguments()}). The response is decoded as a protobuf
 * {@link Message} and placed into a {@link DefaultResponse}.
 * <p>
 * The response parser is provided via the {@code responseParser} parameter
 * in the {@link #request(Request, Parser)} method, or via the URL parameter
 * {@code wireResponseParser} for the standard {@link #request(Request)} path.
 *
 * @author shenhongxi
 */
public class WireClient extends AbstractClient {
    private static final Logger log = LoggerFactory.getLogger(WireClient.class);

    private static final NioEventLoopGroup NIO_EVENT_LOOP = new NioEventLoopGroup();

    private Bootstrap bootstrap;
    private volatile io.netty.channel.Channel channel;

    /**
     * The response parser for decoding gRPC responses. Must be set before
     * calling {@link #request(Request)} via {@link #setResponseParser(Parser)}.
     */
    private volatile Parser<? extends Message> responseParser;

    public WireClient(URL url) {
        super(url);
    }

    /**
     * Set the protobuf parser used to decode response messages.
     * Must be called before {@link #request(Request)}.
     */
    public void setResponseParser(Parser<? extends Message> responseParser) {
        this.responseParser = responseParser;
    }

    @Override
    public synchronized boolean open() {
        if (isAvailable()) {
            return true;
        }

        int timeout = url.getIntParameter(UrlParam.Transport.CONNECT_TIMEOUT);
        if (timeout <= 0) {
            throw new JawsFrameworkException(
                    "WireClient init failed: connect timeout must be positive but was " + timeout);
        }

        bootstrap = new Bootstrap();
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout);
        bootstrap.option(ChannelOption.TCP_NODELAY, true);
        bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        bootstrap.group(NIO_EVENT_LOOP)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast("http2_codec", Http2FrameCodecBuilder.forClient().build());
                        pipeline.addLast("http2_multiplex", new Http2MultiplexHandler(
                                new io.netty.channel.ChannelInboundHandlerAdapter()));
                    }
                });

        doConnect();
        state = ChannelState.ALIVE;

        log.info("WireClient opened successfully: url={}", url);
        return true;
    }

    private void doConnect() {
        ChannelFuture future = bootstrap.connect(url.getHost(), url.getPort())
                .syncUninterruptibly();
        if (!future.isSuccess()) {
            throw new JawsServiceException(
                    "WireClient connect failed: url=" + url.getUri(), future.cause());
        }
        channel = future.channel();
    }

    synchronized void reconnect() {
        if (state.isCloseState()) {
            return;
        }
        if (channel != null && channel.isActive()) {
            return;
        }
        if (channel != null) {
            channel.close();
            channel = null;
        }
        doConnect();
        log.info("WireClient reconnected: url={}", url.getUri());
    }

    @Override
    public Response request(Request request) {
        if (!isAvailable()) {
            throw new JawsServiceException("Wire channel is not available: url=" + url.getUri());
        }

        Parser<? extends Message> parser = this.responseParser;
        if (parser == null) {
            throw new JawsServiceException(
                    "WireClient responseParser not set; call setResponseParser() before request()");
        }

        return request(request, parser);
    }

    /**
     * Send a gRPC request with an explicit response parser.
     *
     * @param request        the RPC request; {@code arguments[0]} must be a protobuf {@link Message}
     * @param responseParser the parser for the expected response message type
     * @return the response containing the decoded protobuf message
     */
    public Response request(Request request, Parser<? extends Message> responseParser) {
        if (!isAvailable()) {
            throw new JawsServiceException("Wire channel is not available: url=" + url.getUri());
        }

        Object[] args = request.getArguments();
        if (args == null || args.length == 0 || !(args[0] instanceof Message)) {
            throw new JawsServiceException(
                    "WireClient request argument must be a protobuf Message; got: "
                            + (args != null && args.length > 0 ? args[0].getClass().getName() : "null"));
        }
        Message requestMessage = (Message) args[0];

        // Build gRPC path: /{interfaceName}/{methodName}
        String grpcPath = "/" + request.getInterfaceName() + "/" + request.getMethodName();

        int timeout = url.getMethodParameter(
                request.getMethodName(), request.getParamDesc(),
                UrlParam.Transport.REQUEST_TIMEOUT.getName(),
                UrlParam.Transport.REQUEST_TIMEOUT.intValue());

        CompletableFuture<Message> resultFuture = new CompletableFuture<>();

        try {
            io.netty.channel.Channel connChannel = channel;
            if (connChannel == null || !connChannel.isActive()) {
                reconnect();
                connChannel = channel;
            }
            if (connChannel == null || !connChannel.isActive()) {
                throw new JawsServiceException("Wire channel is not active: url=" + url.getUri());
            }

            // Open a new HTTP/2 stream
            io.netty.channel.Channel streamChannel =
                    new Http2StreamChannelBootstrap(connChannel)
                            .handler(new WireClientStreamHandler(responseParser, resultFuture))
                            .open().syncUninterruptibly().getNow();

            // Build gRPC request headers
            Http2Headers headers = new DefaultHttp2Headers()
                    .method("POST")
                    .scheme("http")
                    .path(grpcPath)
                    .authority(url.getHostPort())
                    .set(WireConstants.HEADER_CONTENT_TYPE, WireConstants.CONTENT_TYPE_GRPC);

            // Encode request as gRPC frame
            ByteBuf frame = WireFrameCodec.encode(requestMessage, streamChannel.alloc());

            // Send HEADERS + DATA(END_STREAM)
            streamChannel.write(new DefaultHttp2HeadersFrame(headers));
            streamChannel.writeAndFlush(new DefaultHttp2DataFrame(frame, true))
                    .addListener(f -> {
                        if (!f.isSuccess()) {
                            resultFuture.completeExceptionally(
                                    new JawsServiceException("Wire stream write failed", f.cause()));
                        }
                    });

            // Wait for response synchronously
            Message responseMessage = resultFuture.get(timeout, TimeUnit.MILLISECONDS);

            DefaultResponse response = new DefaultResponse(request.getRequestId());
            response.setValue(responseMessage);
            return response;

        } catch (java.util.concurrent.TimeoutException e) {
            throw new JawsServiceException("Wire request timeout: url=" + url.getUri()
                    + " path=" + grpcPath + " timeout=" + timeout + "ms");
        } catch (Exception e) {
            log.error("Wire request failed: url={} path={}", url.getUri(), grpcPath, e);
            if (e instanceof JawsAbstractException jae) {
                throw jae;
            }
            throw new JawsServiceException("WireClient request failed: url="
                    + url.getUri() + " path=" + grpcPath, e);
        }
    }

    /**
     * Send raw protobuf bytes as a gRPC frame. Used by the SPI protocol layer
     * where arguments are opaque bytes rather than typed {@link Message} instances.
     *
     * @param request     the RPC request (interface/method used for gRPC path)
     * @param rawBytes    raw protobuf bytes (without the 5-byte gRPC header)
     * @param responseParser the parser for decoding the response message
     * @return the response containing the decoded protobuf message
     */
    public Response sendRawBytes(Request request, byte[] rawBytes,
                                 Parser<? extends Message> responseParser) {
        if (!isAvailable()) {
            throw new JawsServiceException("Wire channel is not available: url=" + url.getUri());
        }

        String grpcPath = "/" + request.getInterfaceName() + "/" + request.getMethodName();
        int timeout = url.getMethodParameter(
                request.getMethodName(), request.getParamDesc(),
                UrlParam.Transport.REQUEST_TIMEOUT.getName(),
                UrlParam.Transport.REQUEST_TIMEOUT.intValue());

        CompletableFuture<Message> resultFuture = new CompletableFuture<>();

        try {
            io.netty.channel.Channel connChannel = channel;
            if (connChannel == null || !connChannel.isActive()) {
                reconnect();
                connChannel = channel;
            }
            if (connChannel == null || !connChannel.isActive()) {
                throw new JawsServiceException("Wire channel is not active: url=" + url.getUri());
            }

            io.netty.channel.Channel streamChannel =
                    new Http2StreamChannelBootstrap(connChannel)
                            .handler(new WireClientStreamHandler(responseParser, resultFuture))
                            .open().syncUninterruptibly().getNow();

            Http2Headers headers = new DefaultHttp2Headers()
                    .method("POST")
                    .scheme("http")
                    .path(grpcPath)
                    .authority(url.getHostPort())
                    .set(WireConstants.HEADER_CONTENT_TYPE, WireConstants.CONTENT_TYPE_GRPC);

            // Wrap raw protobuf bytes into a gRPC frame
            ByteBuf frame = WireFrameCodec.encodeRawBytes(rawBytes, streamChannel.alloc());

            streamChannel.write(new DefaultHttp2HeadersFrame(headers));
            streamChannel.writeAndFlush(new DefaultHttp2DataFrame(frame, true))
                    .addListener(f -> {
                        if (!f.isSuccess()) {
                            resultFuture.completeExceptionally(
                                    new JawsServiceException("Wire stream write failed", f.cause()));
                        }
                    });

            Message responseMessage = resultFuture.get(timeout, TimeUnit.MILLISECONDS);

            DefaultResponse response = new DefaultResponse(request.getRequestId());
            response.setValue(responseMessage);
            return response;

        } catch (java.util.concurrent.TimeoutException e) {
            throw new JawsServiceException("Wire request timeout: url=" + url.getUri()
                    + " path=" + grpcPath + " timeout=" + timeout + "ms");
        } catch (Exception e) {
            log.error("Wire request failed: url={} path={}", url.getUri(), grpcPath, e);
            if (e instanceof JawsAbstractException jae) {
                throw jae;
            }
            throw new JawsServiceException("WireClient request failed: url="
                    + url.getUri() + " path=" + grpcPath, e);
        }
    }

    @Override
    protected void doClose() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
    }

    @Override
    public boolean isAvailable() {
        if (!super.isAvailable()) {
            return false;
        }
        return channel != null && channel.isOpen();
    }
}
