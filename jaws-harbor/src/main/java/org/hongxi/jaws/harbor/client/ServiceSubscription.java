package org.hongxi.jaws.harbor.client;

import org.hongxi.jaws.harbor.model.ServiceInfo;
import org.hongxi.jaws.harbor.model.ServiceKey;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * One watched service: the redo state of its server-side subscription (Nacos
 * {@code SubscriberRedoData}, whose payload is the cluster list), plus what
 * Nacos keeps elsewhere for the same fact — the listener set and the last
 * believed instance list.
 * <p>
 * The cache is what makes a replay after reconnect correct rather than merely
 * polite: the new connection has to be re-subscribed, and until that reply
 * lands the listeners keep the previous view.
 *
 * @author shenhongxi
 */
final class ServiceSubscription extends RedoData {

    private final ServiceKey key;
    private final String clusters;
    private final List<Consumer<ServiceInfo>> listeners = new CopyOnWriteArrayList<>();
    private volatile ServiceInfo cached;

    ServiceSubscription(ServiceKey key, String clusters) {
        this.key = key;
        this.clusters = clusters;
    }

    ServiceKey key() {
        return key;
    }

    String clusters() {
        return clusters;
    }

    ServiceInfo cached() {
        return cached;
    }

    boolean addListener(Consumer<ServiceInfo> listener) {
        return listeners.add(listener);
    }

    void removeListener(Consumer<ServiceInfo> listener) {
        listeners.remove(listener);
    }

    List<Consumer<ServiceInfo>> listeners() {
        return listeners;
    }

    void cache(ServiceInfo serviceInfo) {
        this.cached = serviceInfo;
    }
}
