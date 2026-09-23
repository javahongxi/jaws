package org.hongxi.jaws.transport.adaptive;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPipeline;

import java.net.SocketAddress;

/**
 * HTTP/1.1 — the opening byte is the first letter of a method.
 * <p>
 * A deliberately loose probe: one byte cannot tell {@code GET} from a random
 * binary stream that happens to start with 'G'. It sits last in the scan
 * anyway, so the two signatures that share its alphabet — TLS ('0x16' never
 * collides) and the h2 preface ('P', resolved decisively at 24 bytes) — have
 * already had their turn.
 */
final class Http1AdaptiveProtocol implements AdaptiveProtocol {

    private final AdaptiveServer server;

    Http1AdaptiveProtocol(AdaptiveServer server) {
        this.server = server;
    }

    @Override
    public String name() {
        return "HTTP/1.1";
    }

    @Override
    public Result detect(ByteBuf in, SocketAddress remote) {
        return isMethodStart(in.getByte(in.readerIndex()))
                ? Result.MATCH : Result.NO;
    }

    /** G=GET, P=OST/UT/ATCH, D=ELETE, H=EAD, O=PTIONS, T=RACE, C=ONNECT. */
    private static boolean isMethodStart(byte b) {
        return b == 'G' || b == 'P' || b == 'D' || b == 'H' || b == 'O' || b == 'T' || b == 'C';
    }

    @Override
    public void install(ChannelPipeline pipeline) {
        server.addHttp1Pipeline(pipeline);
    }
}
