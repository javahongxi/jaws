package org.hongxi.jaws.transport.http2;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.MessageHandler;

/**
 * HTTP/2-based {@link org.hongxi.jaws.transport.Server} implementation built on
 * Netty's {@code Http2FrameCodec} + {@code Http2MultiplexHandler}, speaking plain
 * h2c (HTTP/2 prior-knowledge) without any gRPC/protobuf dependency.
 * <p>
 * Each inbound HTTP/2 stream is initialized with an {@link Http2ServerStreamHandler}
 * that reassembles the request DATA frames, dispatches to the Jaws
 * {@link MessageHandler} pipeline on the business thread pool, and writes the
 * serialized response back on the same stream.
 * <p>
 * Unlike {@code NettyServer}, this server does not use the Jaws binary protocol
 * or its {@link org.hongxi.jaws.transport.Codec}: HTTP/2 framing and flow control
 * are provided by Netty, while business payloads keep using the Jaws
 * {@link org.hongxi.jaws.serialization.Serialization} SPI.
 * <p>
 * Supports both unary and streaming invocations. Streaming methods (server/client/
 * bidirectional) are detected via the {@code x-jaws-streaming} header and dispatched
 * to the provider's {@code callStream()} method.
 * <p>
 * The Netty bootstrap skeleton, business thread pool, GOAWAY-based graceful
 * shutdown, optional TLS with ALPN, and connection limiting are provided by
 * {@link AbstractHttp2Server}.
 * <p>
 * Gateway-friendly enhancements:
 * <ul>
 *   <li>Sends GOAWAY on {@code stopAccept()} so clients migrate to new connections</li>
 *   <li>Built-in {@code GET /health} endpoint for LB HTTP probes</li>
 *   <li>Optional TLS with ALPN (h2 over TLS) when {@code sslCertChain} and
 *       {@code sslPrivateKey} are configured</li>
 * </ul>
 *
 * @author shenhongxi
 */
public class Http2Server extends AbstractHttp2Server {

    private final MessageHandler messageHandler;
    private final String serializationName;
    private final int maxContentLength;

    public Http2Server(URL url, MessageHandler messageHandler) {
        super(url, "Http2Server");
        this.messageHandler = messageHandler;
        this.serializationName = url.getParameter(UrlParam.Transport.SERIALIZATION);
        this.maxContentLength = url.getIntParameter(UrlParam.Transport.MAX_CONTENT_LENGTH);
    }

    @Override
    protected void initStreamChannel(io.netty.channel.Channel streamChannel) {
        streamChannel.pipeline().addLast(new Http2ServerStreamHandler(
                messageHandler, serverExecutor,
                serializationName, activeRequests, maxContentLength));
    }
}
