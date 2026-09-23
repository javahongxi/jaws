package org.hongxi.jaws.transport.adaptive;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPipeline;

import java.net.SocketAddress;

/**
 * Jaws native binary protocol — 2-byte magic {@code 0x4A57} ('J', 'W').
 * <p>
 * The shortest signature on the port, so it decides on 2 bytes; the detector's
 * minimum read window (3) already guarantees they are buffered.
 */
final class JawsBinaryAdaptiveProtocol implements AdaptiveProtocol {

    static final byte MAGIC_HIGH = 0x4A;  // 'J'
    static final byte MAGIC_LOW = 0x57;   // 'W'

    private final AdaptiveServer server;

    JawsBinaryAdaptiveProtocol(AdaptiveServer server) {
        this.server = server;
    }

    @Override
    public String name() {
        return "jaws binary protocol";
    }

    @Override
    public Result detect(ByteBuf in, SocketAddress remote) {
        int idx = in.readerIndex();
        if (in.getByte(idx) == MAGIC_HIGH && in.getByte(idx + 1) == MAGIC_LOW) {
            return Result.MATCH;
        }
        return Result.NO;
    }

    @Override
    public void install(ChannelPipeline pipeline) {
        server.addJawsBinaryPipeline(pipeline);
    }
}
