package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.common.util.NetUtils;
import org.hongxi.jaws.rpc.Referer;
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
 *   		当referers里面包含本地暴露的服务时，并此服务为available的情况下，优先使用此服务。
 * 			当不存在本地暴露的服务时，默认使用低并发ActiveWeight负载均衡策略
 *
 * 		2） 本地服务优先获取策略：
 * 			对referers根据ip顺序查找本地服务，多存在多个本地服务，获取Active最小的本地服务进行服务。
 * 			当不存在本地服务，但是存在远程RPC服务，则根据ActivWeight获取远程RPC服务
 * 			当两者都存在，所有本地服务都应优先于远程服务，本地RPC服务与远程RPC服务内部则根据ActiveWeight进行
 *
 * </pre>
 * 
 * Created by shenhongxi on 2021/4/23.
 */
@SpiMeta(name = "localFirst")
public class LocalFirstLoadBalance<T> extends AbstractLoadBalance<T> {
    
    private static final Logger log = LoggerFactory.getLogger(LocalFirstLoadBalance.class);
    
    public static final int MAX_REFERER_COUNT = 10;

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
    protected Referer<T> doSelect(Request request) {
        List<Referer<T>> referers = getReferers();

        List<Referer<T>> localReferers = searchLocalReferer(referers, NetUtils.getLocalAddress().getHostAddress());

        if (!localReferers.isEmpty()) {
            referers = localReferers;
        }

        int refererSize = referers.size();
        Referer<T> referer = null;

        for (int i = 0; i < refererSize; i++) {
            Referer<T> temp = referers.get(i % refererSize);

            if (!temp.isAvailable()) {
                continue;
            }

            if (referer == null) {
                referer = temp;
            } else {
                if (compare(referer, temp) > 0) {
                    referer = temp;
                }
            }
        }

        return referer;
    }

    @Override
    protected void doSelectToHolder(Request request, List<Referer<T>> refersHolder) {
        List<Referer<T>> referers = getReferers();

        List<Referer<T>> localReferers = searchLocalReferer(referers, NetUtils.getLocalAddress().getHostAddress());

        if (!localReferers.isEmpty()) {
            Collections.sort(localReferers, new LowActivePriorityComparator<T>());
            refersHolder.addAll(localReferers);
        }

        int refererSize = referers.size();
        int startIndex = ThreadLocalRandom.current().nextInt(refererSize);
        int currentCursor = 0;
        int currentAvailableCursor = 0;

        List<Referer<T>> remoteReferers = new ArrayList<Referer<T>>();
        while (currentAvailableCursor < MAX_REFERER_COUNT && currentCursor < refererSize) {
            Referer<T> temp = referers.get((startIndex + currentCursor) % refererSize);
            currentCursor++;

            if (!temp.isAvailable() || localReferers.contains(temp)) {
                continue;
            }

            currentAvailableCursor++;

            remoteReferers.add(temp);
        }

        Collections.sort(remoteReferers, new LowActivePriorityComparator<T>());
        refersHolder.addAll(remoteReferers);
    }

    private List<Referer<T>> searchLocalReferer(List<Referer<T>> referers, String localhost) {
        List<Referer<T>> localReferers = new ArrayList<Referer<T>>();
        long local = ipToLong(localhost);
        for (Referer<T> referer : referers) {
            long tmp = ipToLong(referer.getUrl().getHost());
            if (local != 0 && local == tmp) {
                if (referer.isAvailable()) {
                    localReferers.add(referer);
                }
            }
        }

        return localReferers;
    }

    private int compare(Referer<T> referer1, Referer<T> referer2) {
        return referer1.activeRefererCount() - referer2.activeRefererCount();
    }

    static class LowActivePriorityComparator<T> implements Comparator<Referer<T>> {
        @Override
        public int compare(Referer<T> referer1, Referer<T> referer2) {
            return referer1.activeRefererCount() - referer2.activeRefererCount();
        }
    }
}
