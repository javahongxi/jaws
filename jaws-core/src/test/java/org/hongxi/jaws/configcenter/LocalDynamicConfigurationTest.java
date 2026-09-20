package org.hongxi.jaws.configcenter;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of the in-memory {@link DynamicConfiguration}: the get/set/remove
 * round-trip, the listener notification removal must give, and the tightened
 * {@code setConfig(key, null)} contract.
 *
 * @author shenhongxi
 */
class LocalDynamicConfigurationTest {

    @Test
    void setThenRemoveReturnsPresenceAndClearsValue() {
        LocalDynamicConfiguration config = new LocalDynamicConfiguration();
        config.setConfig("jaws.requestTimeout", "500");
        assertEquals("500", config.getConfig("jaws.requestTimeout"));

        assertTrue(config.removeConfig("jaws.requestTimeout"),
                "removing a present key reports it was there");
        assertNull(config.getConfig("jaws.requestTimeout"),
                "removed key stops existing (resolution falls through)");
    }

    @Test
    void removeOfAbsentKeyIsFalseNotAnError() {
        LocalDynamicConfiguration config = new LocalDynamicConfiguration();
        assertFalse(config.removeConfig("never.set"),
                "a no-op removal returns false, not a fabricated success");
    }

    @Test
    void removalNotifiesListenersWithNull() {
        LocalDynamicConfiguration config = new LocalDynamicConfiguration();
        AtomicReference<String> seen = new AtomicReference<>("unset");
        config.addListener("jaws.retries", (key, value) -> seen.set(value));

        config.setConfig("jaws.retries", "3");
        assertEquals("3", seen.get());

        config.removeConfig("jaws.retries");
        assertNull(seen.get(), "listeners must observe the removal as a null value");
    }

    @Test
    void setConfigRejectsNullInsteadOfSilentlyDeleting() {
        LocalDynamicConfiguration config = new LocalDynamicConfiguration();
        config.setConfig("jaws.route.rule", "some-rule");

        assertThrows(IllegalArgumentException.class,
                () -> config.setConfig("jaws.route.rule", null));
        assertEquals("some-rule", config.getConfig("jaws.route.rule"),
                "the rejected set must not have deleted the existing value");
    }
}
