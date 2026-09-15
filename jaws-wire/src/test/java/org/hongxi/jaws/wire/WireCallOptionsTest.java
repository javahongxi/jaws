package org.hongxi.jaws.wire;

import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link WireCallOptions} (per-call deadline / compressor) and the
 * WireClient resolution seams {@code resolveDeadline} / {@code resolveCompressor}.
 * <p>
 * The core contract: {@link WireCallOptions#DEFAULT} inherits the configured
 * settings (backward compatible), an explicit per-call value overrides them,
 * and the options object is immutable.
 *
 * @author shenhongxi
 */
class WireCallOptionsTest {

    // ---- value object ----

    @Test
    void defaultInheritsEverything() {
        assertNull(WireCallOptions.DEFAULT.deadlineMs());
        assertNull(WireCallOptions.DEFAULT.compressor());
    }

    @Test
    void withDeadlineAndCompressorAreIndependentAndImmutable() {
        WireCallOptions base = WireCallOptions.DEFAULT;

        WireCallOptions withDeadline = base.withDeadlineMs(200);
        assertEquals(200, withDeadline.deadlineMs());
        assertNull(withDeadline.compressor());
        // base untouched (immutability)
        assertNull(base.deadlineMs());

        WireCallOptions withCompressor = base.withCompressor("gzip");
        assertNull(withCompressor.deadlineMs());
        assertEquals("gzip", withCompressor.compressor());

        // chaining keeps both
        WireCallOptions both = base.withDeadlineMs(50).withCompressor("identity");
        assertEquals(50, both.deadlineMs());
        assertEquals("identity", both.compressor());
    }

    @Test
    void nonPositiveDeadlineRejected() {
        assertThrows(IllegalArgumentException.class, () -> WireCallOptions.DEFAULT.withDeadlineMs(0));
        assertThrows(IllegalArgumentException.class, () -> WireCallOptions.DEFAULT.withDeadlineMs(-1));
    }

    // ---- resolution seams (constructed client, never opened) ----

    private static WireClient client(String compression) {
        Map<String, String> params = new HashMap<>();
        params.put("requestTimeout", "5000");
        params.put("compression", compression);
        return new WireClient(new URL("wire", "127.0.0.1", 0, "interop.Greeter", params));
    }

    private static Request request() {
        DefaultRequest r = new DefaultRequest();
        r.setInterfaceName("interop.Greeter");
        r.setMethodName("SayHello");
        r.setArguments(new Object[0]);
        return r;
    }

    @Test
    void resolveDeadlineFallsBackToConfiguredTimeout() {
        WireClient c = client("gzip");
        assertEquals(5000, c.resolveDeadline(request(), WireCallOptions.DEFAULT));
        assertEquals(5000, c.resolveDeadline(request(), null));
    }

    @Test
    void resolveDeadlinePerCallOverrideWins() {
        WireClient c = client("gzip");
        assertEquals(120, c.resolveDeadline(request(), WireCallOptions.DEFAULT.withDeadlineMs(120)));
    }

    @Test
    void resolveCompressorInheritsClientDefault() {
        WireClient c = client("gzip");
        assertEquals("gzip", c.resolveCompressor(WireCallOptions.DEFAULT));
        assertEquals("gzip", c.resolveCompressor(null));
    }

    @Test
    void resolveCompressorPerCallOverrideAndFallback() {
        WireClient c = client("gzip");
        assertEquals("identity", c.resolveCompressor(WireCallOptions.DEFAULT.withCompressor("identity")));
        // unsupported value falls back to the client default
        assertSame("gzip", c.resolveCompressor(WireCallOptions.DEFAULT.withCompressor("snappy")));
    }
}
