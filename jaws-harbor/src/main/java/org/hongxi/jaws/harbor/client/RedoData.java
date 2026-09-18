package org.hongxi.jaws.harbor.client;

/**
 * Redo bookkeeping of one thing this client wants the registry to hold.
 * <p>
 * Three bits rather than one, because "is it registered?" splits three ways:
 * what the caller currently wants ({@code expectedRegistered}), what the server
 * last confirmed ({@code registered}), and whether a removal is already in
 * flight ({@code unregistering}). Withheld the third bit, a deregister whose
 * reply is lost is indistinguishable from one that was never sent, and the
 * instance ghosts on until the session is torn down.
 * <p>
 * Field names, the derived {@link RedoType} and its truth table follow Nacos
 * {@code RedoData}, so a reader who knows one can read the other.
 *
 * @author shenhongxi
 */
abstract class RedoData {

    /** What the caller wants: true to hold it registered, false to be gone. */
    private volatile boolean expectedRegistered = true;

    /** Last confirmed server state; dirtied to false when the session is lost. */
    private volatile boolean registered;

    /** A deregister has been asked for and is not yet confirmed. */
    private volatile boolean unregistering;

    /**
     * What the next pass over this entry should do.
     */
    enum RedoType {
        REGISTER, UNREGISTER, NONE, REMOVE
    }

    RedoType getRedoType() {
        if (registered && !unregistering) {
            return expectedRegistered ? RedoType.NONE : RedoType.UNREGISTER;
        } else if (registered) {
            return RedoType.UNREGISTER;
        } else if (!unregistering) {
            return RedoType.REGISTER;
        }
        // Removal in flight but unconfirmed: the caller may have changed its mind
        // and re-registered meanwhile, in which case intent wins over the stale
        // deregister.
        return expectedRegistered ? RedoType.REGISTER : RedoType.REMOVE;
    }

    boolean isExpectedRegistered() {
        return expectedRegistered;
    }

    void setExpectedRegistered(boolean expectedRegistered) {
        this.expectedRegistered = expectedRegistered;
    }

    boolean isRegistered() {
        return registered;
    }

    boolean isUnregistering() {
        return unregistering;
    }

    /**
     * Confirm the entry is held by the registry.
     */
    void registered() {
        this.registered = true;
        this.unregistering = false;
    }

    /**
     * Confirm the registry no longer holds it; the entry is then spent.
     */
    void unregistered() {
        this.registered = false;
        this.unregistering = true;
    }

    /**
     * Begin (or re-issue) the intent to register.
     */
    void expectRegistered() {
        this.expectedRegistered = true;
        this.unregistering = false;
    }

    /**
     * Begin the intent to deregister. Called before the request goes out, so a
     * lost reply still leaves the removal owed.
     */
    void expectUnregistered() {
        this.expectedRegistered = false;
        this.unregistering = true;
    }

    /**
     * Forget the confirmation. Nacos does this for every entry on disconnect;
     * it is what makes a reconnect replay instead of a no-op.
     */
    void markDirty() {
        this.registered = false;
    }
}
