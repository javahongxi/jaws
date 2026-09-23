package org.hongxi.jaws.transport.adaptive;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPipeline;

import java.net.SocketAddress;

/**
 * TLS — first byte {@code 0x16} (ContentType: Handshake).
 * <p>
 * Installation hands over to the ALPN pipeline, which picks h2 or http/1.1
 * after the handshake. A plaintext-only server receiving a ClientHello refuses
 * the connection here rather than at install time: silently downgrading a
 * client that asked for TLS is the failure mode most worth failing loudly on.
 */
final class TlsAdaptiveProtocol implements AdaptiveProtocol {

    static final byte CONTENT_TYPE_HANDSHAKE = 0x16;

    private final AdaptiveServer server;

    TlsAdaptiveProtocol(AdaptiveServer server) {
        this.server = server;
    }

    @Override
    public String name() {
        return "TLS ClientHello, ALPN pending";
    }

    @Override
    public Result detect(ByteBuf in, SocketAddress remote) {
        if (in.getByte(in.readerIndex()) != CONTENT_TYPE_HANDSHAKE) {
            return Result.NO;
        }
        if (!server.isTlsConfigured()) {
            throw new IllegalStateException(
                    "AdaptiveServer: received TLS ClientHello but TLS is not configured, remote="
                            + remote);
        }
        return Result.MATCH;
    }

    @Override
    public void install(ChannelPipeline pipeline) {
        server.configureTlsPipeline(pipeline);
    }
}
