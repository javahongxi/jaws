package org.hongxi.jaws.transport.http2;

import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;

/**
 * Enumerates the invocation modes supported by the Jaws HTTP/2 transport.
 * <p>
 * Modes are determined by inspecting the service method signature:
 * <ul>
 *   <li>{@link #UNARY} - Traditional request-response (no streaming)</li>
 *   <li>{@link #SERVER} - Client sends one request, server returns a {@link StreamSource}</li>
 *   <li>{@link #CLIENT} - Client streams requests via a {@link StreamObserver}, server returns a single response</li>
 *   <li>{@link #BIDIRECTIONAL} - Client streams requests and server streams responses concurrently</li>
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

    /**
     * Client streaming: client streams multiple requests, server returns a single response.
     */
    CLIENT("client"),

    /**
     * Bidirectional streaming: both client and server stream messages concurrently.
     */
    BIDIRECTIONAL("bidi"),

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
