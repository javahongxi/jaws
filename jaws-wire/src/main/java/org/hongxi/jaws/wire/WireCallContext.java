package org.hongxi.jaws.wire;

import java.util.Collections;
import java.util.Map;

/**
 * Per-call context handed to {@link WireMethodHandler} implementations in
 * direct API mode. Carries the inbound gRPC metadata (custom request headers)
 * so handlers can read tracing IDs, tokens, and other propagated values
 * without depending on the Jaws filter chain.
 * <p>
 * Instances are immutable and safe to share across threads of one call.
 *
 * @author shenhongxi
 */
public final class WireCallContext {

    /** Shared context for calls without any custom metadata. */
    public static final WireCallContext EMPTY = new WireCallContext(Collections.emptyMap());

    private final Map<String, String> attachments;

    private WireCallContext(Map<String, String> attachments) {
        this.attachments = attachments;
    }

    /**
     * Returns a {@link WireCallContext} for the given attachments,
     * reusing {@link #EMPTY} when the map is null or empty.
     */
    public static WireCallContext of(Map<String, String> attachments) {
        return (attachments == null || attachments.isEmpty()) ? EMPTY : new WireCallContext(attachments);
    }

    /**
     * @param name the metadata key (lower case on the wire)
     * @return the metadata value, or {@code null} if absent
     */
    public String getAttachment(String name) {
        return attachments.get(name);
    }

    /**
     * @return an unmodifiable view of all inbound metadata entries
     */
    public Map<String, String> getAttachments() {
        return attachments;
    }
}
