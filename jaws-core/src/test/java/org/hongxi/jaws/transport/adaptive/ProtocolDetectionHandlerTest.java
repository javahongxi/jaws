package org.hongxi.jaws.transport.adaptive;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for the adaptive first-byte detection — these pin the
 * exact dispatch behavior the protocol-registry refactor must preserve: which
 * pipeline lands behind which opening bytes, when the detector waits for more,
 * and when it fails fast.
 * <p>
 * They run against an unopened server on an {@link EmbeddedChannel}: detection
 * only installs handlers, it never executes them, so the null executor and
 * message handler are load-bearing-free — the assertions are about pipeline
 * shape. Fail-fast paths are asserted as "channel closed, no protocol handler
 * installed", because the detector's own {@code exceptionCaught} closes the
 * channel rather than rethrowing to the writer.
 *
 * @author shenhongxi
 */
class ProtocolDetectionHandlerTest {

    private static final String HTTP2_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";

    private static EmbeddedChannel channel() {
        AdaptiveServer server = new AdaptiveServer(
                new URL("adaptive", "127.0.0.1", 0, ""), null);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast("detector", new ProtocolDetectionHandler(server));
        return ch;
    }

    // ========================================================================
    // Positive dispatch
    // ========================================================================

    @Test
    void jawsBinaryMagicInstallsTheDecoderPipeline() {
        EmbeddedChannel ch = channel();
        ch.writeInbound(Unpooled.wrappedBuffer(new byte[]{0x4A, 0x57, 0x01}));
        assertNotNull(ch.pipeline().get("decoder"));
        assertNull(ch.pipeline().get("detector"), "detector must remove itself");
    }

    @Test
    void http2PrefaceInstallsTheHttp2Pipeline() {
        EmbeddedChannel ch = channel();
        ch.writeInbound(Unpooled.wrappedBuffer(
                HTTP2_PREFACE.getBytes(StandardCharsets.US_ASCII)));
        assertNotNull(ch.pipeline().get("http2_codec"));
        assertNull(ch.pipeline().get("detector"));
    }

    @Test
    void http1MethodStartCharsInstallTheHttpPipeline() {
        // G=GET, D=ELETE, H=EAD, O=PTIONS, T=RACE, C=ONNECT — P is covered by
        // the fall-through test below, since it is also the preface's first byte.
        for (char first : new char[]{'G', 'D', 'H', 'O', 'T', 'C'}) {
            EmbeddedChannel ch = channel();
            ch.writeInbound(Unpooled.wrappedBuffer(
                    (first + "ET / HTTP/1.1\r\n").getBytes(StandardCharsets.US_ASCII)));
            assertNotNull(ch.pipeline().get("http_codec"), "for method start " + first);
            assertNull(ch.pipeline().get("detector"));
        }
    }

    // ========================================================================
    // Ambiguity resolution
    // ========================================================================

    @Test
    void partialHttp2PrefaceWaitsForMoreBytes() {
        EmbeddedChannel ch = channel();
        ch.writeInbound(Unpooled.wrappedBuffer(
                "PRI * HT".getBytes(StandardCharsets.US_ASCII)));
        assertNotNull(ch.pipeline().get("detector"), "must wait, not guess");
        assertNull(ch.pipeline().get("http2_codec"));
        assertNull(ch.pipeline().get("http_codec"), "'P' alone must not fall through to HTTP/1");

        ch.writeInbound(Unpooled.wrappedBuffer(
                "TP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
        assertNotNull(ch.pipeline().get("http2_codec"));
        assertNull(ch.pipeline().get("detector"));
    }

    @Test
    void pStartWithoutMatchingPrefaceFallsThroughToHttp1() {
        // >24 bytes so the preface check can complete and answer NO; the request
        // is left header-incomplete on purpose — detection replays the bytes into
        // the installed pipeline, and a full request would drive the (null)
        // business handler rather than just the codec we assert on.
        byte[] post = "POST /api/v1/resource HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII);
        EmbeddedChannel ch = channel();
        ch.writeInbound(Unpooled.wrappedBuffer(post));
        assertNotNull(ch.pipeline().get("http_codec"),
                "'P' + non-preface bytes is a POST, not a broken h2 client");
    }

    @Test
    void fewerThanThreeBytesWaitForMore() {
        EmbeddedChannel ch = channel();
        ch.writeInbound(Unpooled.wrappedBuffer(new byte[]{0x4A}));
        assertNotNull(ch.pipeline().get("detector"));
        assertNull(ch.pipeline().get("decoder"));
    }

    // ========================================================================
    // Fail-fast paths: channel closed, no protocol pipeline installed
    // ========================================================================

    @Test
    void tlsClientHelloWithoutTlsConfiguredFailsFast() {
        EmbeddedChannel ch = channel();
        ch.writeInbound(Unpooled.wrappedBuffer(new byte[]{0x16, 0x03, 0x01, 0x00}));
        assertFalse(ch.isActive(), "a TLS-capable client against a plaintext-only "
                + "server must not be silently downgraded");
        assertNull(ch.pipeline().get("http_codec"));
        assertNull(ch.pipeline().get("http2_codec"));
    }

    @Test
    void unknownFirstBytesFailFast() {
        EmbeddedChannel ch = channel();
        ch.writeInbound(Unpooled.wrappedBuffer(new byte[]{(byte) 0xF0, 0x01, 0x02}));
        assertFalse(ch.isActive(), "an unrecognized opening must close, not guess");
        assertNull(ch.pipeline().get("decoder"));
        assertNull(ch.pipeline().get("http_codec"));
    }
}
