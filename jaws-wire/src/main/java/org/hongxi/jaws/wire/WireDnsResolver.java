package org.hongxi.jaws.wire;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DNS-based service discovery for the gRPC wire client. Resolves a hostname
 * to multiple A/AAAA records and periodically refreshes the resolution to
 * detect address changes (similar to grpc-java's {@code DnsNameResolver}).
 * <p>
 * When enabled, the client connects to all resolved addresses and
 * round-robins requests across them. This provides a basic form of
 * client-side load balancing without requiring an external registry.
 * <p>
 * Configuration via URL parameters:
 * <ul>
 *   <li>{@code dnsEnabled} — whether DNS service discovery is active</li>
 *   <li>{@code dnsRefreshIntervalMs} — how often to re-resolve (default 30s)</li>
 *   <li>{@code dnsDefaultPort} — port to use for resolved addresses</li>
 * </ul>
 *
 * @author shenhongxi
 * @see <a href="https://github.com/grpc/proposal/blob/master/A2.md">gRFC A2: DNS Name Resolution</a>
 */
public class WireDnsResolver {
    private static final Logger log = LoggerFactory.getLogger(WireDnsResolver.class);

    private static final ScheduledExecutorService RESOLVER_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "wire-dns-resolver");
                t.setDaemon(true);
                return t;
            });

    private final String hostname;
    private final int defaultPort;
    private final long refreshIntervalMs;

    private final AtomicReference<List<InetSocketAddress>> resolvedAddresses =
            new AtomicReference<>(List.of());
    private volatile ScheduledFuture<?> refreshFuture;
    private volatile Listener listener;

    /**
     * Callback interface for address list changes.
     */
    public interface Listener {
        /**
         * Called when the resolved address list changes.
         *
         * @param addresses the new list of resolved addresses
         */
        void onAddresses(List<InetSocketAddress> addresses);

        /**
         * Called when DNS resolution fails.
         *
         * @param error the resolution error
         */
        void onError(Throwable error);
    }

    /**
     * @param hostname          the hostname to resolve
     * @param defaultPort       the port for resolved addresses
     * @param refreshIntervalMs how often to re-resolve (0 = resolve once)
     */
    public WireDnsResolver(String hostname, int defaultPort, long refreshIntervalMs) {
        this.hostname = hostname;
        this.defaultPort = defaultPort;
        this.refreshIntervalMs = refreshIntervalMs;
    }

    /**
     * Start the resolver. Performs an initial resolution and schedules
     * periodic refreshes if {@code refreshIntervalMs > 0}.
     *
     * @param listener callback for address changes
     */
    public void start(Listener listener) {
        this.listener = listener;
        // Initial resolution
        resolve();

        // Schedule periodic refresh
        if (refreshIntervalMs > 0) {
            refreshFuture = RESOLVER_SCHEDULER.scheduleAtFixedRate(
                    this::resolve, refreshIntervalMs, refreshIntervalMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Stop the resolver and cancel periodic refreshes.
     */
    public void stop() {
        if (refreshFuture != null) {
            refreshFuture.cancel(false);
            refreshFuture = null;
        }
        listener = null;
    }

    /**
     * @return the most recently resolved addresses, or an empty list if
     *         no resolution has completed yet
     */
    public List<InetSocketAddress> getResolvedAddresses() {
        return resolvedAddresses.get();
    }

    /**
     * Perform a DNS resolution and notify the listener of changes.
     */
    private void resolve() {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(hostname);
            List<InetSocketAddress> socketAddresses = new ArrayList<>(addresses.length);
            for (InetAddress addr : addresses) {
                socketAddresses.add(new InetSocketAddress(addr, defaultPort));
            }

            List<InetSocketAddress> previous = resolvedAddresses.getAndSet(
                    Collections.unmodifiableList(socketAddresses));

            if (!socketAddresses.equals(previous)) {
                log.info("DNS resolution for '{}': {} addresses (was {})",
                        hostname, socketAddresses.size(), previous.size());
                if (log.isDebugEnabled()) {
                    log.debug("DNS addresses for '{}': {}", hostname, socketAddresses);
                }
                Listener l = listener;
                if (l != null) {
                    l.onAddresses(socketAddresses);
                }
            }
        } catch (UnknownHostException e) {
            log.warn("DNS resolution failed for '{}': {}", hostname, e.getMessage());
            Listener l = listener;
            if (l != null) {
                l.onError(e);
            }
        }
    }

    /**
     * Convenience method: resolve a hostname synchronously.
     *
     * @param hostname the hostname to resolve
     * @param port     the port for each resolved address
     * @return the list of resolved socket addresses
     * @throws UnknownHostException if the hostname cannot be resolved
     */
    public static List<InetSocketAddress> resolveNow(String hostname, int port)
            throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(hostname);
        List<InetSocketAddress> result = new ArrayList<>(addresses.length);
        for (InetAddress addr : addresses) {
            result.add(new InetSocketAddress(addr, port));
        }
        return result;
    }

    /**
     * @return the hostname being resolved
     */
    public String getHostname() {
        return hostname;
    }

    /**
     * @return the default port for resolved addresses
     */
    public int getDefaultPort() {
        return defaultPort;
    }
}
