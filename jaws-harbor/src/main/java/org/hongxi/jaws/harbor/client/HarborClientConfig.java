package org.hongxi.jaws.harbor.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings of one {@link HarborClient}: where to connect, which tenant to work
 * in, and how loudly to keep the connection alive.
 * <p>
 * {@code serverList} holds the nodes uniformly as {@code host:port} strings; the
 * first entry is the primary — where we start — and the rest are where we may end
 * up. Named for nacos-client's {@code NamingServerListManager.getServerList()},
 * which is its parsed form of the {@code serverAddr} property
 * {@link #ofCluster(String)} consumes.
 * <p>
 * {@code redoDelayMillis} is the reconcile pass over the redo tables, named after its
 * Nacos counterpart; {@code keepAliveMillis} is not cosmetic. Harbor judges an ephemeral instance
 * unhealthy once its connection has been silent past ~3 beat intervals and
 * retires the connection past ~18, so a client that stays quiet long enough
 * loses its own registrations — this value is that beat.
 *
 * @author shenhongxi
 */
public record HarborClientConfig(List<String> serverList,
                                 String namespace,
                                 String defaultGroup,
                                 long keepAliveMillis,
                                 long redoDelayMillis,
                                 int requestTimeoutMillis,
                                 int connectTimeoutMillis,
                                 int setupTimeoutMillis) {

    public static HarborClientConfig of(String host, int port) {
        return new HarborClientConfig(List.of(host + ":" + port), null, null,
                5_000L, 3_000L, 3_000, 3_000, 5_000);
    }

    /**
     * Parse a comma-separated node list, the same shape as Nacos's {@code serverAddr}:
     * the first entry is where we start, the rest are where we may end up.
     */
    public static HarborClientConfig ofCluster(String serverAddr) {
        List<String> addresses = new ArrayList<>();
        for (String each : serverAddr.split(",")) {
            if (!each.isBlank()) {
                addresses.add(each.trim());
            }
        }
        if (addresses.isEmpty()) {
            throw new IllegalArgumentException("no addresses in server list: " + serverAddr);
        }
        return new HarborClientConfig(List.copyOf(addresses), null, null,
                5_000L, 3_000L, 3_000, 3_000, 5_000);
    }

    public HarborClientConfig {
        serverList = serverList == null ? List.of() : List.copyOf(serverList);
        if (serverList.isEmpty()) {
            throw new IllegalArgumentException("at least one address is required");
        }
        // The primary must be a usable host:port; backups were never validated, and
        // stay that way — only where we start is checked at construction.
        requireHostPort(serverList.get(0));
        namespace = namespace == null || namespace.isEmpty() ? "public" : namespace;
        defaultGroup = defaultGroup == null || defaultGroup.isEmpty()
                ? "DEFAULT_GROUP" : defaultGroup;
        keepAliveMillis = keepAliveMillis <= 0 ? 5_000L : keepAliveMillis;
        // Nacos Constants.DEFAULT_REDO_DELAY_TIME.
        redoDelayMillis = redoDelayMillis <= 0 ? 3_000L : redoDelayMillis;
        requestTimeoutMillis = requestTimeoutMillis <= 0 ? 3_000 : requestTimeoutMillis;
        connectTimeoutMillis = connectTimeoutMillis <= 0 ? 3_000 : connectTimeoutMillis;
        setupTimeoutMillis = setupTimeoutMillis <= 0 ? 5_000 : setupTimeoutMillis;
    }

    private static void requireHostPort(String address) {
        String[] parts = address.split(":");
        if (parts.length != 2 || parts[0].isEmpty()) {
            throw new IllegalArgumentException("address must be host:port: " + address);
        }
        int port;
        try {
            port = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid port in address: " + address, e);
        }
        if (port <= 0) {
            throw new IllegalArgumentException("port must be positive: " + address);
        }
    }

    public HarborClientConfig withKeepAliveMillis(long millis) {
        return new HarborClientConfig(serverList, namespace, defaultGroup,
                millis, redoDelayMillis, requestTimeoutMillis, connectTimeoutMillis,
                setupTimeoutMillis);
    }

    public HarborClientConfig withRedoDelayMillis(long millis) {
        return new HarborClientConfig(serverList, namespace, defaultGroup,
                keepAliveMillis, millis, requestTimeoutMillis, connectTimeoutMillis,
                setupTimeoutMillis);
    }

    /** The node we attach to first. */
    public String primary() {
        return serverList.get(0);
    }

    /** Every node this client may attach to, the primary one first. */
    public List<String> allAddresses() {
        return serverList;
    }
}
