package org.hongxi.jaws.transport.http;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpUtil;

/**
 * Shared response-writing policy for the HTTP/1.1 server: honor the peer's
 * keep-alive preference instead of closing after every response.
 * <p>
 * With keep-alive the response is written and the connection stays open for
 * the next request (HTTP/1.1 needs no header — reuse is the default semantics;
 * HTTP/1.0 gets {@code Connection: keep-alive} via {@link HttpUtil#setKeepAlive}).
 * When the peer asks to close (HTTP/1.0 without keep-alive, or an explicit
 * {@code Connection: close}), the connection is closed after writing, matching
 * the historical per-response close behavior.
 *
 * @author shenhongxi
 */
public final class HttpResponses {

    private HttpResponses() {
    }

    /**
     * Write the response, keeping the connection open or closing it according
     * to the request's keep-alive preference.
     */
    public static void write(ChannelHandlerContext ctx, FullHttpResponse response, boolean keepAlive) {
        HttpUtil.setKeepAlive(response, keepAlive);
        if (keepAlive) {
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
