package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.common.util.MathUtils;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 *
 * Use consistent hash to choose reference
 * <p>
 * Created by shenhongxi on 2021/4/23.
 */
@SpiMeta(name = "consistent")
public class ConsistentHashLoadBalance<T> extends AbstractLoadBalance<T> {

    private List<Reference<T>> consistentHashReferences;

    @Override
    public void onRefresh(List<Reference<T>> references) {
        super.onRefresh(references);

        List<Reference<T>> copyReferences = new ArrayList<Reference<T>>(references);
        List<Reference<T>> tempRefers = new ArrayList<Reference<T>>();
        for (int i = 0; i < JawsConstants.DEFAULT_CONSISTENT_HASH_BASE_LOOP; i++) {
            Collections.shuffle(copyReferences);
            for (Reference<T> ref : copyReferences) {
                tempRefers.add(ref);
            }
        }

        consistentHashReferences = tempRefers;
    }

    @Override
    protected Reference<T> doSelect(Request request) {

        int hash = getHash(request);
        Reference<T> ref;
        for (int i = 0; i < getReferences().size(); i++) {
            ref = consistentHashReferences.get((hash + i) % consistentHashReferences.size());
            if (ref.isAvailable()) {
                return ref;
            }
        }
        return null;
    }

    @Override
    protected void doSelectToHolder(Request request, List<Reference<T>> refersHolder) {
        List<Reference<T>> references = getReferences();

        int hash = getHash(request);
        for (int i = 0; i < references.size(); i++) {
            Reference<T> ref = consistentHashReferences.get((hash + i) % consistentHashReferences.size());
            if (ref.isAvailable()) {
                refersHolder.add(ref);
            }
        }
    }

    private int getHash(Request request) {
        int hashcode;
        if (request.getArguments() == null || request.getArguments().length == 0) {
            hashcode = request.hashCode();
        } else {
            hashcode = Arrays.hashCode(request.getArguments());
        }
        return MathUtils.getNonNegative(hashcode);
    }
}
