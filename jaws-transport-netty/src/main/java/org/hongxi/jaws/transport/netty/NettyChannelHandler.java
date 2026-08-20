package org.hongxi.jaws.transport.netty;

import io.netty.channel.*;
import org.hongxi.jaws.codec.Codec;
import org.hongxi.jaws.transport.FrameEncoder;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.hongxi.jaws.common.util.NetUtils;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
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
                channel.getUrl().getParameter(URLParamType.codec.getName(), URLParamType.codec.value()));
    }

    public NettyChannelHandler(Channel channel, MessageHandler messageHandler, ThreadPoolExecutor threadPoolExecutor) {
        this(channel, messageHandler);
        this.threadPoolExecutor = threadPoolExecutor;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof NettyMessage nettyMsg) {
            if (threadPoolExecutor != null) {
                try {
                    threadPoolExecutor.execute(() -> processMessage(ctx, nettyMsg));
                } catch (RejectedExecutionException rejectException) {
                    // Only server-side requests go through the thread pool;
                    // reject and return error response to client when pool is full.
                    rejectMessage(ctx, nettyMsg);
                }
            } else {
                processMessage(ctx, nettyMsg);
            }
        } else {
            log.error("message type not support: class={}", msg.getClass());
            throw new JawsFrameworkException(
                    "NettyChannelHandler message type not support: class=" + msg.getClass());
        }
    }

    private void rejectMessage(ChannelHandlerContext ctx, NettyMessage msg) {
        sendResponse(ctx, JawsFrameworkUtils.buildErrorResponse(msg.requestId(), new JawsServiceException(
                "process thread pool is full, reject by server: " + ctx.channel().localAddress(),
                                JawsErrorMsgConstants.SERVICE_REJECT)));

        log.error("process thread pool is full, reject, " +
                        "active={} poolSize={} corePoolSize={} maxPoolSize={} taskCount={} requestId={}",
                threadPoolExecutor.getActiveCount(), threadPoolExecutor.getPoolSize(),
                threadPoolExecutor.getCorePoolSize(), threadPoolExecutor.getMaximumPoolSize(),
                threadPoolExecutor.getTaskCount(), msg.requestId());
        if (channel instanceof NettyServer nettyServer) {
            nettyServer.getRejectCounter().incrementAndGet();
        }
    }

    private void processMessage(ChannelHandlerContext ctx, NettyMessage msg) {
        try {
            Object decoded = codec.decode(channel, msg.data());
            if (decoded instanceof Request request) {
                processRequest(ctx, request);
            } else if (decoded instanceof Response response) {
                messageHandler.handleAsync(channel, response);
            }
        } catch (Exception e) {
            log.error("decode failed! requestId: {}, size: {}, remote: {}",
                    msg.requestId(), msg.data().length, ctx.channel().remoteAddress(), e);
            Response response = JawsFrameworkUtils.buildErrorResponse(msg.requestId(), e);
            if (msg.isRequest()) {
                sendResponse(ctx, response);
            } else {
                messageHandler.handleAsync(channel, response);
            }
        }
    }

    private void processRequest(ChannelHandlerContext ctx, Request request) {
        request.setAttachment(URLParamType.host.getName(), NetUtils.getHostName(ctx.channel().remoteAddress()));
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
                    log.error("processRequest failed! request: {}", JawsFrameworkUtils.toString(request), throwable);
                    response = JawsFrameworkUtils.buildErrorResponse(request,
                            new JawsServiceException("process request failed. errmsg:" + throwable.getMessage()));
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
        byte[] msg = FrameEncoder.encodeFrame(channel, codec, response);
        response.setAttachment(CONTENT_LENGTH, String.valueOf(msg.length));
        if (ctx.channel().isActive()) {
            ctx.channel().writeAndFlush(msg);
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