package org.hongxi.jaws.harbor.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings of one {@link HarborClient}: where to connect, which tenant to work
 * in, and how loudly to keep the connection alive.
 * <p>
 * {@code redoDelayMillis} is the reconcile pass over the redo tables, named after its
 * Nacos counterpart; {@code keepAliveMillis} is not cosmetic. Harbor judges an ephemeral instance
 * unhealthy once its connection has been silent past ~3 beat intervals and
 * retires the connection past ~18, so a client that stays quiet long enough
 * loses its own registrations — this value is that beat.
 *
 * @author shenhongxi
 */
public record HarborClientConfig(String host,
                                 int port,
                                 List<String> clusterAddresses,
                                 String namespace,
                                 String defaultGroup,
                                 long keepAliveMillis,
                                 long redoDelayMillis,
                                 int requestTimeoutMillis,
                                 int connectTimeoutMillis,
                                 int setupTimeoutMillis) {

    public static HarborClientConfig of(String host, int port) {
        return new HarborClientConfig(host, port, List.of(), null, null,
                5_000L, 3_000L, 3_000, 3_000, 5_000);
    }

    /**
     * Parse a comma-separated node list, the same shape as Nacos's {@code serverAddr}:
     * the first entry is where we start, the rest are where we may end up.
     */
    public static HarborClientConfig ofCluster(String serverList) {
        List<String> addresses = new ArrayList<>();
        for (String each : serverList.split(",")) {
            if (!each.isBlank()) {
                addresses.add(each.trim());
            }
        }
        if (addresses.isEmpty()) {
            throw new IllegalArgumentException("no addresses in server list: " + serverList);
        }
        String[] first = addresses.get(0).split(":");
        return new HarborClientConfig(first[0], Integer.parseInt(first[1]),
                List.copyOf(addresses.subList(1, addresses.size())), null, null,
                5_000L, 3_000L, 3_000, 3_000, 5_000);
    }

    public HarborClientConfig {
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("host is required");
        }
        if (port <= 0) {
            throw new IllegalArgumentException("port must be positive");
        }
        namespace = namespace == null || namespace.isEmpty() ? "public" : namespace;
        defaultGroup = defaultGroup == null || defaultGroup.isEmpty()
                ? "DEFAULT_GROUP" : defaultGroup;
        clusterAddresses = clusterAddresses == null ? List.of() : List.copyOf(clusterAddresses);
        keepAliveMillis = keepAliveMillis <= 0 ? 5_000L : keepAliveMillis;
        // Nacos Constants.DEFAULT_REDO_DELAY_TIME.
        redoDelayMillis = redoDelayMillis <= 0 ? 3_000L : redoDelayMillis;
        requestTimeoutMillis = requestTimeoutMillis <= 0 ? 3_000 : requestTimeoutMillis;
        connectTimeoutMillis = connectTimeoutMillis <= 0 ? 3_000 : connectTimeoutMillis;
        setupTimeoutMillis = setupTimeoutMillis <= 0 ? 5_000 : setupTimeoutMillis;
    }

    public HarborClientConfig withKeepAliveMillis(long millis) {
        return new HarborClientConfig(host, port, clusterAddresses, namespace, defaultGroup,
                millis, redoDelayMillis, requestTimeoutMillis, connectTimeoutMillis,
                setupTimeoutMillis);
    }

    public HarborClientConfig withRedoDelayMillis(long millis) {
        return new HarborClientConfig(host, port, clusterAddresses, namespace, defaultGroup,
                keepAliveMillis, millis, requestTimeoutMillis, connectTimeoutMillis,
                setupTimeoutMillis);
    }

    /** Every node this client may attach to, the primary one first. */
    public List<String> allAddresses() {
        List<String> all = new ArrayList<>();
        all.add(host + ":" + port);
        all.addAll(clusterAddresses);
        return all;
    }
}
