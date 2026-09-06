package org.hongxi.jaws.transport.mcp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContent;

import java.nio.charset.StandardCharsets;

/**
 * Encodes JSON-RPC messages into Server-Sent Events (SSE) frames for the
 * MCP Streamable HTTP transport.
 * <p>
 * Each SSE frame has the format:
 * <pre>
 * id: {messageId}
 * event: message
 * data: {json}
 *
 * </pre>
 * <p>
 * This encoder produces {@link HttpContent} chunks suitable for chunked
 * transfer encoding responses. The caller is responsible for sending the
 * HTTP response headers ({@code Content-Type: text/event-stream}) before
 * writing SSE frames.
 *
 * @author shenhongxi
 */
public final class SseEncoder {

    private static final byte[] ID_PREFIX = "id: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EVENT_PREFIX = "event: message\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DATA_PREFIX = "data: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEWLINES = "\n\n".getBytes(StandardCharsets.UTF_8);

    private SseEncoder() {
    }

    /**
     * Encode a JSON-RPC message as an SSE frame {@link HttpContent} chunk.
     *
     * @param json      the serialized JSON-RPC message
     * @param messageId unique identifier for the SSE event (used for replay)
     * @return an {@link HttpContent} containing the SSE frame
     */
    public static HttpContent encodeFrame(String json, String messageId) {
        byte[] idBytes = (messageId != null) ? messageId.getBytes(StandardCharsets.UTF_8) : null;
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);

        int size = (idBytes != null ? ID_PREFIX.length + idBytes.length + 1 : 0)
                + EVENT_PREFIX.length
                + DATA_PREFIX.length + jsonBytes.length
                + NEWLINES.length;

        ByteBuf buf = Unpooled.buffer(size);
        if (idBytes != null) {
            buf.writeBytes(ID_PREFIX);
            buf.writeBytes(idBytes);
            buf.writeByte('\n');
        }
        buf.writeBytes(EVENT_PREFIX);
        buf.writeBytes(DATA_PREFIX);
        buf.writeBytes(jsonBytes);
        buf.writeBytes(NEWLINES);
        return new DefaultHttpContent(buf);
    }

    /**
     * Encode a keep-alive comment frame (SSE comment starting with {@code :}).
     * Clients ignore comments, but they keep the connection alive through
     * proxies that might otherwise time out idle connections.
     */
    public static HttpContent encodeKeepAlive() {
        byte[] comment = ": keepalive\n\n".getBytes(StandardCharsets.UTF_8);
        return new DefaultHttpContent(Unpooled.wrappedBuffer(comment));
    }

    /**
     * Write an SSE frame directly through the channel context.
     *
     * @param ctx       the channel handler context
     * @param json      the serialized JSON-RPC message
     * @param messageId unique identifier for the SSE event
     */
    public static void writeFrame(ChannelHandlerContext ctx, String json, String messageId) {
        ctx.writeAndFlush(encodeFrame(json, messageId));
    }
}
