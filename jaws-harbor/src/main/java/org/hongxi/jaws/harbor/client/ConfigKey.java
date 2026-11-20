package org.hongxi.jaws.harbor.client;

/**
 * Identity of one config on the client side — the nacos {@code GroupKey}
 * triple. Normalized once here so the listen table, the push lookup and the
 * wire request all name a key the same way: a blank group becomes
 * {@code DEFAULT_GROUP}, a null tenant becomes the empty string (the server
 * echoes a null tenant in pushes to default-namespace watchers).
 *
 * @author shenhongxi
 */
record ConfigKey(String dataId, String group, String tenant) {

    static ConfigKey of(String dataId, String group, String tenant) {
        return new ConfigKey(dataId,
                group == null || group.isEmpty() ? "DEFAULT_GROUP" : group,
                tenant == null ? "" : tenant);
    }

    @Override
    public String toString() {
        return dataId + "+" + group + "+" + tenant;
    }
}