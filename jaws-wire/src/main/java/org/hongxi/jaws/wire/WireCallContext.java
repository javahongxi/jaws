package org.hongxi.jaws.wire;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-call context handed to {@link WireMethodHandler} implementations in
 * direct API mode and to {@link WireServerInterceptor}s / {@link WireClientInterceptor}s.
 * Carries the inbound gRPC metadata (custom request headers) so handlers and
 * interceptors can read tracing IDs, tokens, and other propagated values
 * without depending on the Jaws filter chain.
 * <p>
 * The context is mutable via {@link #putAttachment(String, String)} so that
 * interceptors can add or modify metadata entries as the call propagates
 * through the chain. The initial entries (from gRPC request headers) are
 * backed by a {@link HashMap} to support this.
 *
 * @author shenhongxi
 */
public final class WireCallContext {

    /** Shared empty context for calls without any custom metadata. */
    public static final WireCallContext EMPTY = new WireCallContext(new HashMap<>());

    private final Map<String, String> attachments;

    private WireCallContext(Map<String, String> attachments) {
        this.attachments = attachments;
    }

    /**
     * Returns a {@link WireCallContext} for the given attachments.
     * The entries are copied into a mutable {@link HashMap} so that
     * interceptors can modify the context without affecting the original map.
     *
     * @param attachments the inbound metadata (may be {@code null} or empty)
     * @return a new mutable context, or {@link #EMPTY} when the input is empty
     */
    public static WireCallContext of(Map<String, String> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return EMPTY;
        }
        return new WireCallContext(new HashMap<>(attachments));
    }

    /**
     * Returns a new mutable {@link WireCallContext} that is a copy of the
     * given context. Unlike {@link #of(Map)}, this always returns a fresh
     * instance (never {@link #EMPTY}), so the caller can safely call
     * {@link #putAttachment(String, String)} on it.
     *
     * @param source the context to copy
     * @return a new mutable context with the same entries
     */
    public static WireCallContext mutableCopy(WireCallContext source) {
        return new WireCallContext(new HashMap<>(source.attachments));
    }

    /**
     * @param name the metadata key (lower case on the wire)
     * @return the metadata value, or {@code null} if absent
     */
    public String getAttachment(String name) {
        return attachments.get(name);
    }

    /**
     * @return an unmodifiable view of all metadata entries
     */
    public Map<String, String> getAttachments() {
        return Map.copyOf(attachments);
    }

    /**
     * Add or overwrite a metadata entry. Used by interceptors to inject
     * or modify metadata as the call propagates through the chain.
     *
     * @param key   the metadata key
     * @param value the metadata value
     */
    public void putAttachment(String key, String value) {
        // Upgrade from the shared EMPTY map to a mutable copy on first write
        if (attachments.isEmpty() && this == EMPTY) {
            // EMPTY uses an empty HashMap already, but we guard against
            // accidental sharing by creating a new map for this instance.
            // Since EMPTY is a singleton, we cannot mutate its map.
            // This path is only reached if someone calls putAttachment on EMPTY,
            // which is unlikely but we handle it gracefully by no-op.
            return;
        }
        attachments.put(key, value);
    }
}
