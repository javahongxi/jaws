package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.common.util.MathUtils;
import org.hongxi.jaws.rpc.Referer;
import org.hongxi.jaws.rpc.Request;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 
 * Use consistent hash to choose referer
 *
 * Created by shenhongxi on 2021/4/23.
 */
@SpiMeta(name = "consistent")
public class ConsistentHashLoadBalance<T> extends AbstractLoadBalance<T> {

    private List<Referer<T>> consistentHashReferers;

    @Override
    public void onRefresh(List<Referer<T>> referers) {
        super.onRefresh(referers);

        List<Referer<T>> copyReferers = new ArrayList<Referer<T>>(referers);
        List<Referer<T>> tempRefers = new ArrayList<Referer<T>>();
        for (int i = 0; i < JawsConstants.DEFAULT_CONSISTENT_HASH_BASE_LOOP; i++) {
            Collections.shuffle(copyReferers);
            for (Referer<T> ref : copyReferers) {
                tempRefers.add(ref);
            }
        }

        consistentHashReferers = tempRefers;
    }

    @Override
    protected Referer<T> doSelect(Request request) {

        int hash = getHash(request);
        Referer<T> ref;
        for (int i = 0; i < getReferers().size(); i++) {
            ref = consistentHashReferers.get((hash + i) % consistentHashReferers.size());
            if (ref.isAvailable()) {
                return ref;
            }
        }
        return null;
    }

    @Override
    protected void doSelectToHolder(Request request, List<Referer<T>> refersHolder) {
        List<Referer<T>> referers = getReferers();

        int hash = getHash(request);
        for (int i = 0; i < referers.size(); i++) {
            Referer<T> ref = consistentHashReferers.get((hash + i) % consistentHashReferers.size());
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
