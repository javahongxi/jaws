package org.hongxi.jaws.wire;

import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link WireMetadata}: the gRPC custom metadata (headers) to Jaws
 * attachments mapping, including reserved-header filtering and case folding.
 *
 * @author shenhongxi
 */
class WireMetadataTest {

    @Test
    void reservedNamesAreFiltered() {
        assertTrue(WireMetadata.isReserved(":path"));
        assertTrue(WireMetadata.isReserved("grpc-timeout"));
        assertTrue(WireMetadata.isReserved("grpc-status"));
        assertTrue(WireMetadata.isReserved("content-type"));
        assertTrue(WireMetadata.isReserved("te"));
        assertTrue(WireMetadata.isReserved("user-agent"));
        assertTrue(WireMetadata.isReserved(null));
        assertTrue(WireMetadata.isReserved(""));
        assertEquals(false, WireMetadata.isReserved("x-trace-id"));
    }

    @Test
    void writeToHeadersLowercasesAndFilters() {
        Map<String, String> attachments = new LinkedHashMap<>();
        attachments.put("X-Trace-Id", "abc123");
        attachments.put("token", "t-1");
        attachments.put("grpc-timeout", "must-be-skipped");
        attachments.put("group", null); // null values skipped

        Http2Headers headers = new DefaultHttp2Headers();
        WireMetadata.writeToHeaders(headers, attachments);

        assertEquals("abc123", headers.get("x-trace-id").toString());
        assertEquals("t-1", headers.get("token").toString());
        assertNull(headers.get("grpc-timeout"));
        assertEquals(2, headers.size());
    }

    @Test
    void fromHeadersExtractsCustomMetadataOnly() {
        Http2Headers headers = new DefaultHttp2Headers()
                .method("POST")
                .path("/svc/Method")
                .set("content-type", WireConstants.CONTENT_TYPE_GRPC)
                .set("te", "trailers")
                .set("grpc-timeout", "5S")
                // HTTP/2 header names are lowercase on the wire
                .set("x-trace-id", "abc123")
                .set("token", "t-1");

        Map<String, String> metadata = WireMetadata.fromHeaders(headers);

        assertEquals(2, metadata.size());
        assertEquals("abc123", metadata.get("x-trace-id"));
        assertEquals("t-1", metadata.get("token"));
    }

    @Test
    void roundTripThroughHeaders() {
        Map<String, String> attachments = new LinkedHashMap<>();
        attachments.put("trace-id", "tid");
        attachments.put("gray", "blue");

        Http2Headers headers = new DefaultHttp2Headers();
        WireMetadata.writeToHeaders(headers, attachments);
        Map<String, String> restored = WireMetadata.fromHeaders(headers);

        assertEquals(attachments, restored);
    }

    @Test
    void emptyAndNullInputs() {
        assertEquals(0, WireMetadata.fromHeaders(null).size());
        assertEquals(0, WireMetadata.fromHeaders(new DefaultHttp2Headers()).size());
        Http2Headers headers = new DefaultHttp2Headers();
        WireMetadata.writeToHeaders(headers, null);
        WireMetadata.writeToHeaders(headers, Map.of());
        assertEquals(0, headers.size());
    }
}
