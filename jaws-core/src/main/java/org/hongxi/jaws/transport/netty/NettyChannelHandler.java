package org.hongxi.jaws.transport.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import org.hongxi.jaws.transport.Codec;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.util.RpcUtils;
import org.hongxi.jaws.common.util.NetUtils;
import org.hongxi.jaws.exception.JawsErrorCode;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.transport.Channel;
import org.hongxi.jaws.transport.MessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Sharable terminal handler of the Netty pipeline that bridges the transport
 * layer and the business layer. Each {@link DecodedFrame} is decoded
 * by the {@link Codec} and dispatched to the {@link MessageHandler}; on the
 * server side this runs on a business {@link ThreadPoolExecutor} so the
 * Netty event loop is never blocked, and a full pool results in an immediate
 * error response rather than queuing. Heartbeat frames never reach this
 * handler — they are consumed earlier by {@link NettyDecoder}.
 * <p>
 * Also encodes response objects back onto the channel and closes the
 * connection on uncaught exceptions.
 * <p>
 * Created by shenhongxi on 2020/7/7.
 */
@ChannelHandler.Sharable
public class NettyChannelHandler extends ChannelDuplexHandler {
    private static final Logger log = LoggerFactory.getLogger(NettyChannelHandler.class);

    private static final String CONTENT_LENGTH = "Content-Length";

    private final Channel channel;
    private final MessageHandler messageHandler;
    private final Codec codec;
    private ThreadPoolExecutor threadPoolExecutor;

    public NettyChannelHandler(Channel channel, MessageHandler messageHandler) {
        this.channel = channel;
        this.messageHandler = messageHandler;
        this.codec = ExtensionLoader.getExtensionLoader(Codec.class).getExtension(
                channel.getUrl().getParameter(UrlParam.Transport.CODEC.getName(), UrlParam.Transport.CODEC.value()));
    }

    public NettyChannelHandler(Channel channel, MessageHandler messageHandler, ThreadPoolExecutor threadPoolExecutor) {
        this(channel, messageHandler);
        this.threadPoolExecutor = threadPoolExecutor;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof DecodedFrame frame) {
            try {
                if (threadPoolExecutor != null) {
                    try {
                        // Retain the ByteBuf for async processing (pipeline may release after this method returns)
                        frame.data().retain();
                        threadPoolExecutor.execute(() -> {
                            try {
                                processFrame(ctx, frame);
                            } finally {
                                frame.data().release();
                            }
                        });
                    } catch (RejectedExecutionException rejectException) {
                        // Release the ByteBuf retained above; the finally block below only releases
                        // the reference acquired by the decoder (readRetainedSlice).
                        frame.data().release();
                        // Only server-side requests go through the thread pool;
                        // reject and return error response to client when pool is full.
                        rejectFrame(ctx, frame);
                    }
                } else {
                    processFrame(ctx, frame);
                }
            } finally {
                // Release the ByteBuf retained by the decoder (readRetainedSlice)
                frame.data().release();
            }
        } else {
            log.error("unsupported message type: class={}", msg.getClass());
            throw new JawsFrameworkException(
                    "NettyChannelHandler received unsupported message type: class=" + msg.getClass());
        }
    }

    private void rejectFrame(ChannelHandlerContext ctx, DecodedFrame frame) {
        sendResponse(ctx, RpcUtils.buildErrorResponse(frame.requestId(), new JawsServiceException(
                "request rejected by server due to full thread pool: " + ctx.channel().localAddress(),
                                JawsErrorCode.SERVICE_REJECT)));

        log.error("request rejected due to full thread pool, " +
                        "active={} poolSize={} corePoolSize={} maxPoolSize={} taskCount={} requestId={}",
                threadPoolExecutor.getActiveCount(), threadPoolExecutor.getPoolSize(),
                threadPoolExecutor.getCorePoolSize(), threadPoolExecutor.getMaximumPoolSize(),
                threadPoolExecutor.getTaskCount(), frame.requestId());
        if (channel instanceof NettyServer nettyServer) {
            nettyServer.getRejectCounter().incrementAndGet();
        }
    }

    private void processFrame(ChannelHandlerContext ctx, DecodedFrame frame) {
        try {
            Object decoded = codec.decode(channel, frame.data());
            if (decoded instanceof Request request) {
                processRequest(ctx, request);
            } else if (decoded instanceof Response response) {
                messageHandler.handleAsync(channel, response);
            }
        } catch (Exception e) {
            log.error("Failed to decode, requestId: {}, size: {}, remote: {}",
                    frame.requestId(), frame.data().readableBytes(), ctx.channel().remoteAddress(), e);
            Response response = RpcUtils.buildErrorResponse(frame.requestId(), e);
            if (frame.isRequest()) {
                sendResponse(ctx, response);
            } else {
                messageHandler.handleAsync(channel, response);
            }
        }
    }

    private void processRequest(ChannelHandlerContext ctx, Request request) {
        request.setAttachment(UrlParam.Server.HOST.getName(), NetUtils.getHostName(ctx.channel().remoteAddress()));
        final long processStartTime = System.currentTimeMillis();
        // Track active request for graceful shutdown
        if (channel instanceof NettyServer nettyServer) {
            nettyServer.getActiveRequests().incrementAndGet();
        }
        RpcContext.init(request);
        messageHandler.handleAsync(channel, request).whenComplete((res, throwable) -> {
            try {
                RpcContext.init(request);
                DefaultResponse response;
                if (throwable != null) {
                    log.error("Failed to process request: {}", RpcUtils.toString(request), throwable);
                    response = RpcUtils.buildErrorResponse(request,
                            new JawsServiceException("process request failed: " + throwable.getMessage()));
                } else if (res instanceof DefaultResponse dr) {
                    response = dr;
                } else if (res instanceof Response r) {
                    response = new DefaultResponse(r);
                } else {
                    response = new DefaultResponse(res);
                }
                response.setRequestId(request.getRequestId());
                response.setProcessTime(System.currentTimeMillis() - processStartTime);

                sendResponse(ctx, response);
            } finally {
                if (channel instanceof NettyServer nettyServer) {
                    nettyServer.getActiveRequests().decrementAndGet();
                }
                RpcContext.destroy();
            }
        });
    }

    private void sendResponse(ChannelHandlerContext ctx, Response response) {
        ByteBuf buf = ctx.alloc().buffer();
        try {
            codec.encode(channel, response, buf);
        } catch (Exception e) {
            buf.release();
            log.error("encode response error: requestId={}", response.getRequestId(), e);
            return;
        }
        response.setAttachment(CONTENT_LENGTH, String.valueOf(buf.readableBytes()));
        if (ctx.channel().isActive()) {
            ctx.channel().writeAndFlush(buf);
        } else {
            buf.release();
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.info("channelActive: remote={} local={}", ctx.channel().remoteAddress(), ctx.channel().localAddress());
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("channelInactive: remote={} local={}", ctx.channel().remoteAddress(), ctx.channel().localAddress());
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("exceptionCaught: remote={} local={} event={}",
                ctx.channel().remoteAddress(), ctx.channel().localAddress(), cause.getMessage(), cause);
        ctx.channel().close();
    }
}