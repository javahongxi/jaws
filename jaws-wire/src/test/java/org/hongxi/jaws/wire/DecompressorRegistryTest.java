package org.hongxi.jaws.wire;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DecompressorRegistry} semantics: the advertised set versus the
 * known set, immutability, and the point of the whole exercise — registering a
 * codec is all it takes for a stream to negotiate and decode it.
 *
 * @author shenhongxi
 */
class DecompressorRegistryTest {

    private static final int MAX_MESSAGE_SIZE = 4 * 1024 * 1024;

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

    /**
     * gzip under another name — the "fzip" trick grpc-java's own
     * {@code CompressionTest} uses, so a test can prove a codec was selected by
     * registration rather than hardcoded.
     */
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
    void defaultAdvertisesGzipButKnowsIdentityToo() {
        DecompressorRegistry registry = DecompressorRegistry.getDefaultInstance();

        // identity is registered without being advertised: an uncompressed
        // frame needs no agreement, so naming it in the header adds nothing
        assertEquals("gzip", registry.rawAdvertisedEncodings());
        assertEquals(Set.of("gzip", "identity"), registry.getKnownMessageEncodings());
        assertEquals(Set.of("gzip"), registry.getAdvertisedMessageEncodings());
        assertSame(Codec.Identity.NONE, registry.lookupDecompressor("identity"));
    }

    @Test
    void emptyInstanceOffersNothingAndDecodesNothing() {
        DecompressorRegistry registry = DecompressorRegistry.emptyInstance();

        assertEquals("", registry.rawAdvertisedEncodings());
        assertTrue(registry.getKnownMessageEncodings().isEmpty());
        assertNull(registry.lookupDecompressor("identity"));
    }

    @Test
    void withReturnsANewTableAndLeavesTheOriginalAlone() {
        DecompressorRegistry base = DecompressorRegistry.getDefaultInstance();
        DecompressorRegistry wider = base.with(new Fzip(), true);

        assertNotSame(base, wider);
        assertEquals("gzip", base.rawAdvertisedEncodings(),
                "a derived table must not mutate the one it came from");
        assertEquals("gzip,fzip", wider.rawAdvertisedEncodings());
        assertNotNull(wider.lookupDecompressor("fzip"));
        assertNull(base.lookupDecompressor("fzip"));
    }

    @Test
    void registrationUnderAnExistingNameReplacesOnlyThatName() {
        DecompressorRegistry base = DecompressorRegistry.getDefaultInstance()
                .with(new Fzip(), true);
        Codec freshGzip = new Codec.Gzip();
        DecompressorRegistry again = base.with(freshGzip, true);

        // Replacing a name keeps every other registration and moves that name
        // to the end of the table, which is also the order it is advertised in
        assertEquals(Set.of("gzip", "fzip", "identity"), again.getKnownMessageEncodings());
        assertSame(freshGzip, again.lookupDecompressor("gzip"),
                "the last registration under a name wins");
        assertEquals("fzip,gzip", again.rawAdvertisedEncodings());
    }

    @Test
    void lookupIgnoresWhetherTheCodecWasAdvertised() {
        DecompressorRegistry hidden = DecompressorRegistry.getDefaultInstance()
                .with(new Fzip(), false);

        assertEquals("gzip", hidden.rawAdvertisedEncodings());
        assertNotNull(hidden.lookupDecompressor("fzip"),
                "the wire format says we decode what we know how to decode,"
                        + " whether or not we asked for it");
    }

    @Test
    void encodingNamesWithACommaAreRejected() {
        DecompressorRegistry registry = DecompressorRegistry.emptyInstance();
        assertThrows(IllegalArgumentException.class,
                () -> registry.with(new Named("a,b"), true));
    }

    /**
     * The real claim of this refactor: a third encoding becomes usable by
     * registering it, with no change to framing, negotiation or header writing.
     */
    @Test
    void registeredCodecIsNegotiatedAndDecodedEndToEnd() throws Exception {
        DecompressorRegistry registry = DecompressorRegistry.getDefaultInstance()
                .with(new Fzip(), true);
        WireHandlerRegistry handlers = new WireHandlerRegistry();
        handlers.register("test.Health", "Echo", new WireMethodHandler() {
            @Override
            public Message handle(Message request) {
                return HealthCheckResponse.newBuilder()
                        .setStatus(ServingStatus.SERVING).build();
            }

            @Override
            public Parser<? extends Message> getRequestParser() {
                return HealthCheckRequest.parser();
            }
        });
        EmbeddedChannel ch = new EmbeddedChannel(new WireStreamServerHandler(
                new WireCallDispatcher.HandlerCallDispatcher(handlers, Set.of()),
                null, DIRECT_EXECUTOR, MAX_MESSAGE_SIZE, 0, null, registry, null));

        ch.writeInbound(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()
                .method("POST").scheme("http").path("/test.Health/Echo").authority("localhost")
                .set(WireConstants.HEADER_CONTENT_TYPE, WireConstants.CONTENT_TYPE_GRPC)
                .set(WireConstants.HEADER_TE, WireConstants.TE_TRAILERS)
                .set(WireConstants.GRPC_ENCODING, "fzip"), false));
        ByteBuf frame = WireFrameCodec.encode(
                HealthCheckRequest.newBuilder().setService("demo").build(),
                ch.alloc(), new Fzip());
        ch.writeInbound(new DefaultHttp2DataFrame(frame, true));

        Http2HeadersFrame responseHeaders = ch.readOutbound();
        assertEquals("gzip,fzip",
                responseHeaders.headers().get(WireConstants.GRPC_ACCEPT_ENCODING).toString(),
                "the advertisement comes from the configured registry");
        Http2DataFrame data = ch.readOutbound();
        ByteBuf copy = data.content().copy();
        try {
            assertEquals(ServingStatus.SERVING,
                    WireFrameCodec.decode(copy, HealthCheckResponse.parser()).getStatus(),
                    "a request framed with a registered codec must decode");
        } finally {
            copy.release();
        }
        ch.finishAndReleaseAll();
    }

    /** Minimal codec with an explicit name, for validation tests. */
    private static final class Named implements Decompressor {
        private final String encoding;

        Named(String encoding) {
            this.encoding = encoding;
        }

        @Override
        public String getMessageEncoding() {
            return encoding;
        }

        @Override
        public InputStream decompress(InputStream is) {
            return is;
        }
    }
}
