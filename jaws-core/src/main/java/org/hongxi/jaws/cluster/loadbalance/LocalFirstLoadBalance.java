package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.common.util.NetUtils;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * "本地服务优先" 负载均衡
 * <p>
 * <pre>
 * 		1） 本地服务优先：
 *   		当references里面包含本地暴露的服务时，并此服务为available的情况下，优先使用此服务。
 * 			当不存在本地暴露的服务时，默认使用低并发ActiveWeight负载均衡策略
 *
 * 		2） 本地服务优先获取策略：
 * 			对references根据ip顺序查找本地服务，多存在多个本地服务，获取Active最小的本地服务进行服务。
 * 			当不存在本地服务，但是存在远程RPC服务，则根据ActivWeight获取远程RPC服务
 * 			当两者都存在，所有本地服务都应优先于远程服务，本地RPC服务与远程RPC服务内部则根据ActiveWeight进行
 *
 * </pre>
 * <p>
 * Created by shenhongxi on 2021/4/23.
 */
@SpiMeta(name = "localFirst")
public class LocalFirstLoadBalance<T> extends AbstractLoadBalance<T> {

    public static final int MAX_REFERENCE_COUNT = 10;
    private static final Logger log = LoggerFactory.getLogger(LocalFirstLoadBalance.class);

    public static long ipToLong(final String addr) {
        final String[] addressBytes = addr.split("\\.");
        int length = addressBytes.length;
        if (length < 3) {
            return 0;
        }
        long ip = 0;
        try {
            for (int i = 0; i < 4; i++) {
                ip <<= 8;
                ip |= Integer.parseInt(addressBytes[i]);
            }
        } catch (Exception e) {
            log.warn("Warn ipToInt addr is wrong: addr={}", addr);
        }

        return ip;
    }

    @Override
    protected Reference<T> doSelect(Request request) {
        List<Reference<T>> references = getReferences();

        List<Reference<T>> localReferences = searchLocalReference(references, NetUtils.getLocalAddress().getHostAddress());

        if (!localReferences.isEmpty()) {
            references = localReferences;
        }

        int referenceSize = references.size();
        Reference<T> reference = null;

        for (int i = 0; i < referenceSize; i++) {
            Reference<T> temp = references.get(i % referenceSize);

            if (!temp.isAvailable()) {
                continue;
            }

            if (reference == null) {
                reference = temp;
            } else {
                if (compare(reference, temp) > 0) {
                    reference = temp;
                }
            }
        }

        return reference;
    }

    @Override
    protected void doSelectToHolder(Request request, List<Reference<T>> refersHolder) {
        List<Reference<T>> references = getReferences();

        List<Reference<T>> localReferences = searchLocalReference(references, NetUtils.getLocalAddress().getHostAddress());

        if (!localReferences.isEmpty()) {
            Collections.sort(localReferences, new LowActivePriorityComparator<T>());
            refersHolder.addAll(localReferences);
        }

        int referenceSize = references.size();
        int startIndex = ThreadLocalRandom.current().nextInt(referenceSize);
        int currentCursor = 0;
        int currentAvailableCursor = 0;

        List<Reference<T>> remoteReferences = new ArrayList<Reference<T>>();
        while (currentAvailableCursor < MAX_REFERENCE_COUNT && currentCursor < referenceSize) {
            Reference<T> temp = references.get((startIndex + currentCursor) % referenceSize);
            currentCursor++;

            if (!temp.isAvailable() || localReferences.contains(temp)) {
                continue;
            }

            currentAvailableCursor++;

            remoteReferences.add(temp);
        }

        Collections.sort(remoteReferences, new LowActivePriorityComparator<T>());
        refersHolder.addAll(remoteReferences);
    }

    private List<Reference<T>> searchLocalReference(List<Reference<T>> references, String localhost) {
        List<Reference<T>> localReferences = new ArrayList<Reference<T>>();
        long local = ipToLong(localhost);
        for (Reference<T> reference : references) {
            long tmp = ipToLong(reference.getUrl().getHost());
            if (local != 0 && local == tmp) {
                if (reference.isAvailable()) {
                    localReferences.add(reference);
                }
            }
        }

        return localReferences;
    }

    private int compare(Reference<T> reference1, Reference<T> reference2) {
        return reference1.activeReferenceCount() - reference2.activeReferenceCount();
    }

    static class LowActivePriorityComparator<T> implements Comparator<Reference<T>> {
        @Override
        public int compare(Reference<T> reference1, Reference<T> reference2) {
            return reference1.activeReferenceCount() - reference2.activeReferenceCount();
        }
    }
}
