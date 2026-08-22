package org.hongxi.jaws.rpc;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression check: toFullStr()/valueOf() must round-trip parameter values
 * containing URL-reserved characters (&, =, %), while legacy unencoded
 * strings keep parsing exactly as before.
 */
class URLTest {

    @Test
    void roundTripPreservesReservedCharacters() {
        Map<String, String> params = new HashMap<>();
        params.put("route", "a=1&b=2");
        params.put("percent", "100%");
        params.put("plain", "value");
        URL url = new URL("jaws", "127.0.0.1", 20880, "org.hongxi.jaws.DemoService", params);

        URL parsed = URL.valueOf(url.toFullStr());

        assertEquals("a=1&b=2", parsed.getParameter("route"));
        assertEquals("100%", parsed.getParameter("percent"));
        assertEquals("value", parsed.getParameter("plain"));
        assertEquals(url, parsed);
    }

    @Test
    void legacyUnencodedValuesStillParse() {
        URL parsed = URL.valueOf("jaws://127.0.0.1:20880/org.hongxi.jaws.DemoService?group=g1&timeout=3000");

        assertEquals("g1", parsed.getParameter("group"));
        assertEquals(3000, parsed.getParameter("timeout", 0));
    }

    @Test
    void legacyValueWithStrayPercentIsKeptAsIs() {
        URL parsed = URL.valueOf("jaws://127.0.0.1:20880/svc?progress=50%");

        assertEquals("50%", parsed.getParameter("progress"));
    }
}
