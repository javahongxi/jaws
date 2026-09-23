package org.hongxi.jaws.transport.adaptive;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPipeline;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * HTTP/2 cleartext (h2c prior-knowledge) — the 24-byte connection preface
 * {@code PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n}.
 * <p>
 * The only candidate with a multi-byte signature long enough to straddle a TCP
 * segment, hence the sole user of {@link Result#NEED_MORE}: a partial preface
 * pauses the scan instead of leaking the connection to HTTP/1.1, which also
 * starts with 'P' (POST/PUT/PATCH). Once 24 bytes are buffered the answer is
 * final either way — a mismatch falls through to HTTP/1.1 as a plain POST.
 */
final class Http2AdaptiveProtocol implements AdaptiveProtocol {

    /** Length of the HTTP/2 connection preface. */
    static final int PREFACE_LENGTH = 24;

    private static final byte[] PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private final AdaptiveServer server;

    Http2AdaptiveProtocol(AdaptiveServer server) {
        this.server = server;
    }

    @Override
    public String name() {
        return "HTTP/2 h2c";
    }

    @Override
    public Result detect(ByteBuf in, SocketAddress remote) {
        int idx = in.readerIndex();
        if (in.getByte(idx) != 'P') {
            return Result.NO;
        }
        if (in.readableBytes() < PREFACE_LENGTH) {
            return Result.NEED_MORE;
        }
        for (int i = 0; i < PREFACE_LENGTH; i++) {
            if (in.getByte(idx + i) != PREFACE[i]) {
                return Result.NO;
            }
        }
        return Result.MATCH;
    }

    @Override
    public void install(ChannelPipeline pipeline) {
        server.addHttp2Pipeline(pipeline);
    }
}
