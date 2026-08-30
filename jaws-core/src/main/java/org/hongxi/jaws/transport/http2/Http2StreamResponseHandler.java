package org.hongxi.jaws.transport.http2;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.util.ReferenceCountUtil;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.ResponseFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Per-stream response handler for the HTTP/2 client.
 * <p>
 * One instance lives on each stream channel created by {@link Http2Client}.
 * It collects the response HEADERS/DATA frames, decodes the payload on
 * END_STREAM and completes the matching {@link ResponseFuture} registered
 * in the client's callback map. On stream reset or premature close it fails
 * the future so callers never hang beyond the request timeout.
 *
 * @author shenhongxi
 */
class Http2StreamResponseHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(Http2StreamResponseHandler.class);

    private final Http2Client client;
    private final long requestId;

    private String status;
    private ByteArrayOutputStream buffer;

    Http2StreamResponseHandler(Http2Client client, long requestId) {
        this.client = client;
        this.requestId = requestId;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof Http2HeadersFrame headersFrame) {
            status = Objects.toString(headersFrame.headers().status(), null);
            if (headersFrame.isEndStream()) {
                // no payload, e.g. server-side error reported via headers only
                complete(new DefaultResponse(requestId));
            }
        } else if (msg instanceof Http2DataFrame dataFrame) {
            onData(dataFrame);
        } else if (msg instanceof Http2ResetFrame resetFrame) {
            fail("HTTP/2 stream reset: errorCode=" + resetFrame.errorCode());
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    private void onData(Http2DataFrame dataFrame) {
        try {
            ByteBuf content = dataFrame.content();
            if (buffer == null) {
                buffer = new ByteArrayOutputStream(content.readableBytes());
            }
            byte[] bytes = new byte[content.readableBytes()];
            content.readBytes(bytes);
            buffer.write(bytes, 0, bytes.length);

            if (dataFrame.isEndStream()) {
                complete(decode());
            }
        } finally {
            dataFrame.release();
        }
    }

    private DefaultResponse decode() {
        DefaultResponse response = new DefaultResponse(requestId);
        try {
            if (!Http2Constants.STATUS_OK.equals(status)) {
                String body = buffer != null ? buffer.toString(StandardCharsets.UTF_8) : "";
                response.setException(new JawsServiceException(
                        "HTTP/2 transport error: status=" + status + ", message=" + body));
                return response;
            }
            if (buffer == null) {
                return response;
            }
            return Http2PayloadCodec.decodeResponse(buffer.toByteArray(), client.getSerialization());
        } catch (Exception e) {
            log.error("Failed to decode HTTP/2 response: requestId={}", requestId, e);
            response.setException(new JawsServiceException(
                    "Failed to decode response", e));
            return response;
        }
    }

    private void complete(DefaultResponse response) {
        ResponseFuture future = client.removeCallback(requestId);
        if (future == null) {
            // already timed out or canceled
            return;
        }
        if (response.getException() != null) {
            future.onFailure(response);
        } else {
            future.onSuccess(response);
        }
    }

    private void fail(String message) {
        DefaultResponse response = new DefaultResponse(requestId);
        response.setException(new JawsServiceException(message));
        complete(response);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // stream/connection closed before the response arrived
        fail("HTTP/2 stream closed before response received");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("HTTP/2 stream error: requestId={}", requestId, cause);
        fail("HTTP/2 stream error: " + cause.getMessage());
        ctx.close();
    }
}
