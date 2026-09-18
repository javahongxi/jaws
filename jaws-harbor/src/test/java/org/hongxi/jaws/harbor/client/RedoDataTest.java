package org.hongxi.jaws.harbor.client;

import org.hongxi.jaws.harbor.model.Instance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decision table the client's recovery rests on, pinned directly rather
 * than through a network failure that cannot be injected without a fake
 * transport. Rows follow Nacos {@code RedoData.getRedoType()}.
 *
 * @author shenhongxi
 */
class RedoDataTest {

    private static InstanceRedoData entry() {
        Instance instance = new Instance();
        instance.setIp("127.0.0.1");
        instance.setPort(9000);
        return new InstanceRedoData(instance);
    }

    @Test
    void freshEntryOwesARegistration() {
        InstanceRedoData data = entry();
        assertTrue(data.isExpectedRegistered(), "intent defaults to holding it registered");
        assertEquals(RedoData.RedoType.REGISTER, data.getRedoType());
    }

    @Test
    void confirmedRegistrationNeedsNothing() {
        InstanceRedoData data = entry();
        data.registered();
        assertEquals(RedoData.RedoType.NONE, data.getRedoType());
    }

    @Test
    void lostConnectionDirtiesTheConfirmationBackToRegister() {
        InstanceRedoData data = entry();
        data.registered();
        data.markDirty();
        assertEquals(RedoData.RedoType.REGISTER, data.getRedoType(),
                "without dirtying, a replay after reconnect would be a no-op");
    }

    @Test
    void pendingDeregisterIsOwedUntilConfirmed() {
        InstanceRedoData data = entry();
        data.registered();
        data.expectUnregistered();
        // Marked but the request has not been confirmed: still owed.
        assertEquals(RedoData.RedoType.UNREGISTER, data.getRedoType());
    }

    @Test
    void confirmedDeregisterSpendsTheEntry() {
        InstanceRedoData data = entry();
        data.registered();
        data.expectUnregistered();
        data.unregistered();
        assertEquals(RedoData.RedoType.REMOVE, data.getRedoType());
        assertFalse(data.isExpectedRegistered());
    }

    @Test
    void deregisterWithoutPriorConfirmationIsStillOwed() {
        InstanceRedoData data = entry();
        data.expectUnregistered();
        // Never confirmed registered and a removal in flight: intent says go,
        // so the entry is spent rather than re-registered.
        assertEquals(RedoData.RedoType.REMOVE, data.getRedoType());
    }

    @Test
    void reRegistrationBeatsAnInFlightDeregister() {
        InstanceRedoData data = entry();
        data.expectUnregistered();
        data.expectRegistered();
        // The caller changed its mind while the removal was in flight.
        assertEquals(RedoData.RedoType.REGISTER, data.getRedoType());
    }

    @Test
    void deregisterOfAnUnconfirmedRegistrationStillOwesRemoval() {
        InstanceRedoData data = entry();
        data.registered();
        data.setExpectedRegistered(false);
        assertEquals(RedoData.RedoType.UNREGISTER, data.getRedoType(),
                "confirmed present but no longer wanted: remove it");
    }
}
