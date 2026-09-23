package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import org.hongxi.jaws.wire.health.HealthCheckRequest;
import org.hongxi.jaws.wire.health.HealthCheckResponse;
import org.hongxi.jaws.wire.health.HealthCheckResponse.ServingStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link CompressorRegistry} semantics: lookup, registration as the only way to
 * widen the selectable set, and a response actually compressed by a codec that
 * exists nowhere in the framework's own code.
 *
 * @author shenhongxi
 */
class CompressorRegistryTest {

    private static final int MAX_MESSAGE_SIZE = 4 * 1024 * 1024;
    private static final HealthCheckRequest REQUEST =
            HealthCheckRequest.newBuilder().setService("demo").build();

    private static final ExecutorService DIRECT_EXECUTOR = new AbstractExecutorService() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    };

    /** gzip under another name, as in grpc-java's own {@code CompressionTest}. */
    private static final class Fzip implements Codec {
        private final Codec delegate = new Codec.Gzip();

        @Override
        public String getMessageEncoding() {
            return "fzip";
        }

        @Override
        public OutputStream compress(OutputStream os) throws IOException {
            return delegate.compress(os);
        }

        @Override
        public InputStream decompress(InputStream is) throws IOException {
            return delegate.decompress(is);
        }
    }

    @Test
    void defaultKnowsGzipAndIdentityAndNothingElse() {
        CompressorRegistry registry = CompressorRegistry.getDefaultInstance();

        assertNotNull(registry.lookupCompressor("gzip"));
        assertSame(Codec.Identity.NONE, registry.lookupCompressor("identity"),
                "identity must be the sentinel framing compares by reference");
        assertNull(registry.lookupCompressor("zstd"));
    }

    @Test
    void emptyInstanceKnowsNothingUntilRegistered() {
        CompressorRegistry registry = CompressorRegistry.newEmptyInstance();

        assertNull(registry.lookupCompressor("gzip"));
        registry.register(new Fzip());
        assertNotNull(registry.lookupCompressor("fzip"));
    }

    @Test
    void registrationUnderAnExistingNameReplacesIt() {
        CompressorRegistry registry = CompressorRegistry.newEmptyInstance();
        registry.register(new Codec.Gzip());
        Compressor replacement = new Named("gzip");
        registry.register(replacement);

        assertSame(replacement, registry.lookupCompressor("gzip"),
                "the last registration under a name wins");
    }

    @Test
    void encodingNamesWithACommaAreRejected() {
        CompressorRegistry registry = CompressorRegistry.newEmptyInstance();
        assertThrows(IllegalArgumentException.class, () -> registry.register(new Named("a,b")));
    }

    /**
     * The point of the registry on the outbound leg: configuring a compressor
     * this framework has never heard of makes the response use it, given a
     * client that advertises it.
     */
    @Test
    void registeredCompressorIsUsedForTheResponse() throws Exception {
        WireHandlerRegistry handlers = new WireHandlerRegistry();
        handlers.register("test.Health", "Echo", new WireMethodHandler() {
            @Override
            public Message handle(Message request) {
                return HealthCheckResponse.newBuilder().setStatus(ServingStatus.SERVING).build();
            }

            @Override
            public Parser<? extends Message> getRequestParser() {
                return HealthCheckRequest.parser();
            }
        });
        EmbeddedChannel ch = new EmbeddedChannel(new WireStreamServerHandler(
                new WireCallDispatcher.HandlerCallDispatcher(handlers, Set.of()),
                null, DIRECT_EXECUTOR, MAX_MESSAGE_SIZE, 0, new Fzip(),
                DecompressorRegistry.emptyInstance(), null));

        ch.writeInbound(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()
                .method("POST").scheme("http").path("/test.Health/Echo").authority("localhost")
                .set(WireConstants.HEADER_CONTENT_TYPE, WireConstants.CONTENT_TYPE_GRPC)
                .set(WireConstants.HEADER_TE, WireConstants.TE_TRAILERS)
                .set(WireConstants.GRPC_ACCEPT_ENCODING, "fzip"), false));
        ch.writeInbound(new DefaultHttp2DataFrame(
                WireFrameCodec.encode(REQUEST, ch.alloc()), true));

        Http2HeadersFrame responseHeaders = ch.readOutbound();
        assertEquals("fzip",
                responseHeaders.headers().get(WireConstants.GRPC_ENCODING).toString(),
                "the advertised-in response names the registered codec");
        Http2DataFrame data = ch.readOutbound();
        assertEquals(WireConstants.COMPRESSED, data.content().getByte(0),
                "and the frame flag says so");
        // An empty decompressor registry advertises nothing at all
        assertNull(responseHeaders.headers().get(WireConstants.GRPC_ACCEPT_ENCODING));
        ch.finishAndReleaseAll();
    }

    /** Minimal compressor with an explicit name, for validation tests. */
    private static final class Named implements Compressor {
        private final String encoding;

        Named(String encoding) {
            this.encoding = encoding;
        }

        @Override
        public String getMessageEncoding() {
            return encoding;
        }

        @Override
        public OutputStream compress(OutputStream os) {
            return os;
        }
    }
}
