package org.hongxi.jaws.harbor.model;

import java.util.Objects;

/**
 * Identity of a service: the triplet that every index in Harbor is keyed by.
 * <p>
 * Introduced because a joined {@code "namespace@@group@@serviceName"} string used as
 * a map key forces each consumer to re-parse what it already had in hand — Harbor
 * had eight {@code split} sites whose only purpose was to hand the three parts back
 * to a callback. A key object also makes group/name prefix confusion impossible in
 * {@code listServices}, where a string prefix scan used to do the matching.
 * <p>
 * This is the Nacos shape: its storage maps are keyed by
 * {@code core/v2/pojo/Service} — {@code ConcurrentMap<Service, ServiceInfo>
 * serviceDataIndexes} in {@code core/v2/index/ServiceStorage} — with {@code equals}
 * and {@code hashCode} over {@code (namespace, group, name)} only, deliberately
 * excluding {@code ephemeral}. We keep the same three components and the same
 * identity semantics, and use a record so the fields are final by construction.
 * <p>
 * The wire format stays a string: {@code ClientSyncData.serviceKeys} and
 * {@code Instance.instanceId} are external protocol, so {@link #toKeyString()} and
 * {@link #parse(String)} exist exactly for those boundaries. Do not add a cached raw
 * string field: a record hash over three {@code String}s is not more expensive than
 * hashing the concatenated form, since each segment's hash is itself cached by
 * {@code String}.
 *
 * @param namespace tenant, {@code public} when unset
 * @param group     service group, {@code DEFAULT_GROUP} by default
 * @param name      service name
 *
 * @author shenhongxi
 */
public record ServiceKey(String namespace, String group, String name) {

    /** Separator of the legacy string key form, kept for the wire only. */
    public static final String SEPARATOR = "@@";

    public ServiceKey {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(name, "name");
    }

    public static ServiceKey of(String namespace, String group, String name) {
        return new ServiceKey(namespace, group, name);
    }

    /**
     * Rebuild a key from its wire/log form {@code namespace@@group@@name}.
     *
     * @throws IllegalArgumentException when the string does not hold three parts —
     *         a malformed key must fail loudly rather than index a wrong service
     */
    public static ServiceKey parse(String keyString) {
        String[] parts = keyString.split(SEPARATOR, 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("malformed service key: " + keyString);
        }
        return new ServiceKey(parts[0], parts[1], parts[2]);
    }

    /** @return the wire/log form, i.e. the concatenation this object replaces as a key. */
    public String toKeyString() {
        return namespace + SEPARATOR + group + SEPARATOR + name;
    }

    /** @return the {@code group@@name} form carried inside {@code instanceId}. */
    public String groupedName() {
        return group + SEPARATOR + name;
    }

    @Override
    public String toString() {
        return toKeyString();
    }
}
