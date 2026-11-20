package org.hongxi.jaws.harbor.client;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * One watched config: the redo state of its server-side listen (the config
 * counterpart of {@link ServiceSubscription}), plus the listener set and the
 * last believed content and its md5.
 * <p>
 * The cached md5 is what makes both the listen round and the change push
 * cheap: the server only reports keys whose stored md5 differs, and a pulled
 * content that still matches the held md5 notifies nobody. A null content
 * with an empty md5 is the "removed" state, mirroring what nacos-client hands
 * its listeners on {@code CONFIG_NOT_FOUND}.
 *
 * @author shenhongxi
 */
final class ConfigListenData extends RedoData {

    private final ConfigKey key;
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private volatile String content;
    private volatile String md5 = "";

    ConfigListenData(ConfigKey key) {
        this.key = key;
    }

    ConfigKey key() {
        return key;
    }

    String content() {
        return content;
    }

    String md5() {
        return md5;
    }

    void cache(String content, String md5) {
        this.content = content;
        this.md5 = md5 == null ? "" : md5;
    }

    void addListener(Consumer<String> listener) {
        listeners.add(listener);
    }

    void removeListener(Consumer<String> listener) {
        listeners.remove(listener);
    }

    List<Consumer<String>> listeners() {
        return listeners;
    }
}