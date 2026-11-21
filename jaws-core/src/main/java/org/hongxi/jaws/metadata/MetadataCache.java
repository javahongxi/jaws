package org.hongxi.jaws.metadata;

import java.io.Serial;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Consumer-side revision cache shared by all application-level directories,
 * the minimal counterpart of Dubbo's {@code MetaCacheManager} (memory LRU only,
 * no file persistence).
 * <p>
 * Entries are self-validating: both {@link #get} and {@link #put} recompute the
 * content revision and compare it with the key, so a corrupted or mismatched
 * entry can never be served — the content-addressing invariant is enforced on
 * every access, not only when the entry is created.
 */
public final class MetadataCache {

    /** Maximum number of cached revisions, same default as Dubbo. */
    private static final int MAX_ENTRIES = 100;

    private static final Map<String, MetadataInfo> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Serial
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, MetadataInfo> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    private MetadataCache() {
    }

    /**
     * @return the cached metadata for the revision, or null on miss or when the
     *         entry fails content validation (the bad entry is then evicted)
     */
    public static MetadataInfo get(String revision) {
        MetadataInfo info = CACHE.get(revision);
        if (info != null && !isValid(revision, info)) {
            CACHE.remove(revision);
            return null;
        }
        return info;
    }

    /**
     * Cache metadata under its revision.
     *
     * @return true if stored; false when the content does not hash to the key
     */
    public static boolean put(String revision, MetadataInfo info) {
        if (isValid(revision, info)) {
            CACHE.put(revision, info);
            return true;
        }
        return false;
    }

    /** Drop all entries; for tests only. */
    public static void clear() {
        CACHE.clear();
    }

    private static boolean isValid(String revision, MetadataInfo info) {
        return revision != null && info != null
                && revision.equals(info.getRevision())
                && revision.equals(info.calRevision());
    }
}