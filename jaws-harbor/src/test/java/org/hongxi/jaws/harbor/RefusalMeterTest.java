package org.hongxi.jaws.harbor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The volume a refused request type may generate, and the count that must
 * survive its suppression.
 *
 * @author shenhongxi
 */
class RefusalMeterTest {

    private static final String CONFIG_QUERY = "ConfigQueryRequest";
    private static final String CONFIG_PUBLISH = "ConfigPublishRequest";

    @Test
    void firstSightingIsSaidOnceAndTheRestAreCounted() throws Exception {
        RefusalMeter meter = new RefusalMeter(10_000);
        assertTrue(meter.shouldLog(CONFIG_QUERY), "the boundary has to be stated once");
        assertEquals(0, meter.suppressedSince(CONFIG_QUERY), "nothing was swallowed yet");

        for (int retry = 0; retry < 20; retry++) {
            assertFalse(meter.shouldLog(CONFIG_QUERY), "retries stay quiet inside the window");
        }
        assertEquals(20, meter.suppressedSince(CONFIG_QUERY),
                "the line that does get written must carry the volume behind it");
        assertEquals(0, meter.suppressedSince(CONFIG_QUERY), "and the count is spent once read");
    }

    @Test
    void theWindowReopensSoTheProblemCannotVanishSilently() throws Exception {
        RefusalMeter meter = new RefusalMeter(30);
        assertTrue(meter.shouldLog(CONFIG_QUERY));
        assertFalse(meter.shouldLog(CONFIG_QUERY));
        Thread.sleep(120);
        assertTrue(meter.shouldLog(CONFIG_QUERY),
                "a client still retrying must be reported again, not muted forever");
    }

    @Test
    void eachRefusedTypeKeepsItsOwnLedger() {
        RefusalMeter meter = new RefusalMeter(10_000);
        assertTrue(meter.shouldLog(CONFIG_QUERY));
        assertFalse(meter.shouldLog(CONFIG_QUERY));
        assertTrue(meter.shouldLog(CONFIG_PUBLISH),
                "a second refused type is a different boundary statement");
        assertEquals(1, meter.suppressedSince(CONFIG_QUERY));
        assertEquals(0, meter.suppressedSince(CONFIG_PUBLISH));
    }

    @Test
    void anUnknownTypeIsCountedSeparatelyFromTheDeclaredOnes() {
        RefusalMeter meter = new RefusalMeter(10_000);
        assertTrue(meter.shouldLog("SomethingNobodyAskedForRequest"));
        assertTrue(meter.shouldLog(CONFIG_QUERY));
        assertEquals(0, meter.suppressedSince("SomethingNobodyAskedForRequest"));
    }
}
