package org.hongxi.jaws.wire;

import io.netty.handler.codec.http2.Http2Headers;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bidirectional mapping between gRPC custom metadata (HTTP/2 headers) and
 * Jaws request attachments.
 * <p>
 * gRPC reserves all header names starting with {@code grpc-} plus the
 * HTTP/2 pseudo-headers and transport-level headers ({@code content-type},
 * {@code te}, {@code user-agent}); those are never mapped to attachments.
 * Custom metadata keys are case-insensitive and carried in lower case on
 * the wire.
 *
 * @author shenhongxi
 */
public final class WireMetadata {

    /**
     * Estimate the total size of HTTP/2 headers in bytes, computed as the
     * sum of each header name and value byte lengths. This matches the
     * HTTP/2 specification's header list size calculation (RFC 7540 §4.2),
     * without the 32-byte per-entry overhead added by the HPACK encoder.
     *
     * @param headers the HTTP/2 headers to measure
     * @return the estimated size in bytes
     */
    public static int estimateHeaderSize(Http2Headers headers) {
        if (headers == null || headers.isEmpty()) {
            return 0;
        }
        int size = 0;
        for (Map.Entry<CharSequence, CharSequence> entry : headers) {
            size += entry.getKey().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            size += entry.getValue().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        return size;
    }

    private WireMetadata() {
    }

    /**
     * @param name the header name (case-insensitive)
     * @return true if the header is reserved by HTTP/2 or gRPC and must not
     *         be mapped to a user attachment
     */
    public static boolean isReserved(String name) {
        if (name == null || name.isEmpty()) {
            return true;
        }
        if (name.charAt(0) == ':') {
            return true;
        }
        if (name.startsWith("grpc-")) {
            return true;
        }
        return "content-type".equals(name) || "te".equals(name) || "user-agent".equals(name);
    }

    /**
     * Write non-reserved attachments into HTTP/2 headers as gRPC metadata.
     * Keys are lower-cased; reserved keys and null values are skipped.
     *
     * @param headers     the headers to write into
     * @param attachments the call attachments, may be null or empty
     */
    public static void writeToHeaders(Http2Headers headers, Map<String, String> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : attachments.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (isReserved(key) || entry.getValue() == null) {
                continue;
            }
            headers.set(key, entry.getValue());
        }
    }

    /**
     * Extract non-reserved headers from a request/response as call metadata.
     *
     * @param headers the inbound headers
     * @return an unmodifiable map of metadata entries (lower-cased keys),
     *         empty when none
     */
    public static Map<String, String> fromHeaders(Http2Headers headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        for (Map.Entry<CharSequence, CharSequence> entry : headers) {
            String key = entry.getKey().toString().toLowerCase(Locale.ROOT);
            if (!isReserved(key)) {
                metadata.put(key, entry.getValue().toString());
            }
        }
        return Collections.unmodifiableMap(metadata);
    }
}
