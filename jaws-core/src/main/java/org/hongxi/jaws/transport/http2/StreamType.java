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

    private final String wireValue;

    StreamType(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * The value used on the wire in the {@code x-jaws-streaming} header.
     */
    public String getWireValue() {
        return wireValue;
    }

    /**
     * Resolve a {@link StreamType} from its wire value.
     *
     * @param wireValue the header value (e.g., "server")
     * @return the corresponding StreamType, or {@link #UNARY} if null/unknown
     */
    public static StreamType fromWireValue(String wireValue) {
        if (wireValue == null) {
            return UNARY;
        }
        for (StreamType type : values()) {
            if (type.wireValue.equals(wireValue)) {
                return type;
            }
        }
        return UNARY;
    }
}
