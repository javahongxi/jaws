package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Consistent hash load balance based on a virtual-node hash ring.
 * <p>
 * Each reference is mapped to {@link #VIRTUAL_NODES} virtual nodes on the ring.
 * A request is routed to the reference owning the next virtual node clockwise
 * from the request hash; when that reference is unavailable or filtered out by
 * routers, the walk continues clockwise. Because only virtual nodes of changed
 * references move on refresh, most requests keep hitting the same provider,
 * which a shuffle-based arrangement cannot guarantee.
 * <p>
 * Created by shenhongxi on 2021/4/23.
 */
@Extension("consistentHash")
public class ConsistentHashLoadBalance<T> extends AbstractLoadBalance<T> {

    private static final int VIRTUAL_NODES = 160;

    // volatile: rebuilt by the notify thread in onRefresh, read by business threads on every select
    private volatile TreeMap<Integer, Reference<T>> ring = new TreeMap<>();

    @Override
    public void onRefresh(List<Reference<T>> references) {
        super.onRefresh(references);

        TreeMap<Integer, Reference<T>> newRing = new TreeMap<>();
        for (Reference<T> ref : references) {
            String key = referenceKey(ref);
            for (int i = 0; i < VIRTUAL_NODES; i++) {
                // probe for a free slot so a hash collision never overwrites a node
                int position = hash(key + "#" + i);
                while (newRing.containsKey(position)) {
                    position++;
                }
                newRing.put(position, ref);
            }
        }
        this.ring = newRing;
    }

    @Override
    protected Reference<T> doSelect(List<Reference<T>> references, Request request) {
        return nextMatch(getHash(request), references, null);
    }

    @Override
    protected void doSelectCandidates(List<Reference<T>> references, Request request,
                                        List<Reference<T>> candidates) {
        Map<Reference<T>, Boolean> seen = new IdentityHashMap<>();
        int hash = getHash(request);
        while (candidates.size() < MAX_REFERENCE_COUNT) {
            Reference<T> ref = nextMatch(hash, references, seen);
            if (ref == null) {
                break;
            }
            candidates.add(ref);
            seen.put(ref, Boolean.TRUE);
        }
    }

    /**
     * Walk the ring clockwise from {@code hash} and return the first reference
     * that is available, belongs to the router-filtered list, and has not been
     * excluded; {@code null} when the walk comes full circle without a match.
     */
    private Reference<T> nextMatch(int hash, List<Reference<T>> references, Map<Reference<T>, ?> exclude) {
        TreeMap<Integer, Reference<T>> currentRing = this.ring;
        if (currentRing.isEmpty()) {
            return null;
        }
        Map.Entry<Integer, Reference<T>> entry = currentRing.ceilingEntry(hash);
        if (entry == null) {
            entry = currentRing.firstEntry();
        }
        Integer start = entry.getKey();
        while (true) {
            Reference<T> ref = entry.getValue();
            if (ref.isAvailable() && references.contains(ref) && (exclude == null || !exclude.containsKey(ref))) {
                return ref;
            }
            entry = currentRing.higherEntry(entry.getKey());
            if (entry == null) {
                entry = currentRing.firstEntry();
            }
            if (entry.getKey().equals(start)) {
                return null;
            }
        }
    }

    private String referenceKey(Reference<T> ref) {
        if (ref.getServiceUrl() != null) {
            return ref.getServiceUrl().getHostPort();
        }
        // Fallback key for references without a service url (e.g. test stubs).
        // Going through FNV below instead of hashing the identity code
        // directly gives the key the full 2^31 space, making collisions
        // between distinct references far less likely.
        return ref.getClass().getName() + "@" + System.identityHashCode(ref);
    }

    private int getHash(Request request) {
        Object[] arguments = request.getArguments();
        if (arguments == null || arguments.length == 0) {
            return hash(String.valueOf(request.hashCode()));
        }
        // Arrays.hashCode of short similar strings clusters tightly, which
        // may land a batch of keys inside one ring arc; FNV1a over the
        // string form spreads them across the ring.
        return hash(Arrays.deepToString(arguments));
    }

    /**
     * FNV1a-32 hash to spread virtual nodes across the full integer space;
     * {@link String#hashCode} collisions here would silently overwrite ring
     * entries and break the mapping stability guarantee.
     */
    private static int hash(String key) {
        int h = 0x811C9DC5;
        for (int i = 0; i < key.length(); i++) {
            h = (h ^ key.charAt(i)) * 0x01000193;
        }
        return h & 0x7fffffff;
    }
}
