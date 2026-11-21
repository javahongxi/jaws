package org.hongxi.jaws.metadata;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Self-validation and LRU eviction of the consumer-side {@link MetadataCache}:
 * a key must always equal the content hash, on both put and get.
 */
class MetadataCacheTest {

    @BeforeEach
    void setUp() {
        MetadataCache.clear();
    }

    private MetadataInfo metadata(int port) {
        Map<String, String> params = new HashMap<>();
        params.put(UrlParam.Identity.ENDPOINT_TYPE.getName(), JawsConstants.ENDPOINT_TYPE_SERVICE);
        MetadataInfo info = new MetadataInfo("app-a");
        info.addServiceUrl(new URL("jaws", "10.0.0.1", port, "com.example.DemoService", params));
        info.init();
        return info;
    }

    @Test
    void putThenGetRoundtrip() {
        MetadataInfo info = metadata(8080);
        assertTrue(MetadataCache.put(info.getRevision(), info));
        assertEquals(info, MetadataCache.get(info.getRevision()));
    }

    @Test
    void putRejectsKeyContentMismatch() {
        MetadataInfo info = metadata(8080);
        assertFalse(MetadataCache.put("not-the-content-hash", info));
        assertNull(MetadataCache.get("not-the-content-hash"));
    }

    @Test
    void getEvictsTamperedEntry() {
        MetadataInfo info = metadata(8080);
        String revision = info.getRevision();
        MetadataCache.put(revision, info);

        // Simulate corruption: content no longer hashes to the key.
        info.getServices().put("extra", "jaws://10.0.0.1:9999/com.example.Other");
        assertNull(MetadataCache.get(revision), "tampered entry must not be served");
        assertNull(MetadataCache.get(revision), "tampered entry must have been evicted");
    }

    @Test
    void lruEvictsBeyondCapacity() {
        MetadataInfo first = metadata(10000);
        MetadataCache.put(first.getRevision(), first);
        for (int port = 10001; port <= 10100; port++) {
            MetadataInfo info = metadata(port);
            MetadataCache.put(info.getRevision(), info);
        }
        assertNull(MetadataCache.get(first.getRevision()),
                "the eldest entry is evicted once capacity (100) is exceeded");
        MetadataInfo last = metadata(10100);
        assertEquals(last.getRevision(), MetadataCache.get(last.getRevision()).getRevision());
    }
}