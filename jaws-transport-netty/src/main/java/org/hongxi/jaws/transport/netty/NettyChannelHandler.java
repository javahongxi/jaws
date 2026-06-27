package org.hongxi.jaws.transport.netty;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import org.hongxi.jaws.codec.Codec;
import org.hongxi.jaws.codec.CodecUtils;
import org.hongxi.jaws.common.JawsConstants;
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

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Created by shenhongxi on 2020/7/7.
 */
public class NettyChannelHandler extends ChannelDuplexHandler {
    private static final Logger log = LoggerFactory.getLogger(NettyChannelHandler.class);

    private ThreadPoolExecutor threadPoolExecutor;
    private MessageHandler messageHandler;
    private Channel channel;
    private Codec codec;

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
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        if (msg instanceof NettyMessage nettyMsg) {
            if (threadPoolExecutor != null) {
                try {
                    threadPoolExecutor.execute(() -> processMessage(ctx, nettyMsg));
                } catch (RejectedExecutionException rejectException) {
                    if (nettyMsg.isRequest()) {
                        rejectMessage(ctx, nettyMsg);
                    } else {
                        log.warn("process thread pool is full, run in io thread, " +
                                        "active={} poolSize={} corePoolSize={} maxPoolSize={} taskCount={} requestId={}",
                                threadPoolExecutor.getActiveCount(), threadPoolExecutor.getPoolSize(),
                                threadPoolExecutor.getCorePoolSize(), threadPoolExecutor.getMaximumPoolSize(),
                                threadPoolExecutor.getTaskCount(), nettyMsg.getRequestId());
                        processMessage(ctx, nettyMsg);
                    }
                }
            } else {
                processMessage(ctx, nettyMsg);
            }
        } else {
            log.error("messageReceived type not support: class={}", msg.getClass());
            throw new JawsFrameworkException(
                    "NettyChannelHandler messageReceived type not support: class=" + msg.getClass());
        }
    }

    private void rejectMessage(ChannelHandlerContext ctx, NettyMessage msg) {
        if (msg.isRequest()) {
            sendResponse(ctx,
                    JawsFrameworkUtils.buildErrorResponse(
                            (Request) msg,
                            new JawsServiceException(
                                    "process thread pool is full, reject by server: "
                                            + ctx.channel().localAddress(),
                                    JawsErrorMsgConstants.SERVICE_REJECT
                            )
                    )
            );

            log.error("process thread pool is full, reject, " +
                            "active={} poolSize={} corePoolSize={} maxPoolSize={} taskCount={} requestId={}",
                    threadPoolExecutor.getActiveCount(), threadPoolExecutor.getPoolSize(),
                    threadPoolExecutor.getCorePoolSize(), threadPoolExecutor.getMaximumPoolSize(),
                    threadPoolExecutor.getTaskCount(), msg.getRequestId());
            if (channel instanceof NettyServer nettyServer) {
                nettyServer.getRejectCounter().incrementAndGet();
            }
        }
    }

    private void processMessage(ChannelHandlerContext ctx, NettyMessage msg) {
        String remoteIp = getRemoteIp(ctx);
        Object result;
        try {
            result = codec.decode(channel, remoteIp, msg.getData());
        } catch (Exception e) {
            log.error("NettyDecoder decode fail! requestid: {}, size: {}, ip: {}",
                    msg.getRequestId(), msg.getData().length, remoteIp, e);
            Response response = JawsFrameworkUtils.buildErrorResponse(msg.getRequestId(), e);
            if (msg.isRequest()) {
                sendResponse(ctx, response);
            } else {
                processResponse(response);
            }
            return;
        }

        if (result instanceof Request request) {
            processRequest(ctx, request);
        } else if (result instanceof Response response) {
            processResponse(response);
        }
    }

    private void processRequest(final ChannelHandlerContext ctx, final Request request) {
        request.setAttachment(URLParamType.host.getName(), NetUtils.getHostName(ctx.channel().remoteAddress()));
        final long processStartTime = System.currentTimeMillis();
        try {
            RpcContext.init(request);
            Object result;
            try {
                result = messageHandler.handle(channel, request);
            } catch (Exception e) {
                log.error("processRequest fail! request: {}", JawsFrameworkUtils.toString(request), e);
                result = JawsFrameworkUtils.buildErrorResponse(request,
                        new JawsServiceException("process request fail. errmsg:" + e.getMessage()));
            }
            final DefaultResponse response;
            if (result instanceof DefaultResponse defaultResponse) {
                response = defaultResponse;
            } else {
                response = new DefaultResponse(result);
            }
            response.setRequestId(request.getRequestId());
            response.setProcessTime(System.currentTimeMillis() - processStartTime);

            ChannelFuture channelFuture = sendResponse(ctx, response);
            if (channelFuture != null) {
                channelFuture.addListener((ChannelFutureListener) future -> response.onFinish());
            }
        } finally {
            RpcContext.destroy();
        }
    }

    private ChannelFuture sendResponse(ChannelHandlerContext ctx, Response response) {
        byte[] msg = CodecUtils.encodeObjectToBytes(channel, codec, response);
        response.setAttachment(JawsConstants.CONTENT_LENGTH, String.valueOf(msg.length));
        if (ctx.channel().isActive()) {
            return ctx.channel().writeAndFlush(msg);
        }
        return null;
    }

    private void processResponse(Object msg) {
        messageHandler.handle(channel, msg);
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

    private String getRemoteIp(ChannelHandlerContext ctx) {
        String ip = "";
        SocketAddress remote = ctx.channel().remoteAddress();
        if (remote instanceof InetSocketAddress inetAddr) {
            try {
                ip = inetAddr.getAddress().getHostAddress();
            } catch (Exception e) {
                log.warn("get remoteIp error! default will use. msg:{}, remote:{}", e.getMessage(), remote);
            }
        }
        return ip;
    }
}
