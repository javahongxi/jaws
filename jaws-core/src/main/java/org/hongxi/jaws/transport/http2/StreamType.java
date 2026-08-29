package org.hongxi.jaws.transport.http2;

/**
 * Enumerates the invocation modes supported by the Jaws HTTP/2 transport.
 * <p>
 * Modes are determined by inspecting the service method signature:
 * <ul>
 *   <li>{@link #UNARY} - Traditional request-response (no streaming)</li>
 *   <li>{@link #SERVER} - Client sends one request, server returns a {@link java.util.concurrent.Flow.Publisher}</li>
 * </ul>
 * <p>
 * The mode is communicated on the wire via the {@code x-jaws-streaming} HTTP/2 header.
 *
 * @author shenhongxi
 */
public enum StreamType {

    /**
     * Traditional unary invocation: one request, one response.
     */
    UNARY("unary"),

    /**
     * Server streaming: client sends one request, server streams multiple responses.
     */
    SERVER("server"),

    ;

    private final String value;

    StreamType(String value) {
        this.value = value;
    }

    /**
     * The value used in the {@code x-jaws-streaming} header.
     */
    public String getValue() {
        return value;
    }

    /**
     * Resolve a {@link StreamType} from its header value.
     *
     * @param value the header value (e.g., "server")
     * @return the corresponding StreamType, or {@link #UNARY} if null/unknown
     */
    public static StreamType fromValue(String value) {
        if (value == null) {
            return UNARY;
        }
        for (StreamType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return UNARY;
    }
}
