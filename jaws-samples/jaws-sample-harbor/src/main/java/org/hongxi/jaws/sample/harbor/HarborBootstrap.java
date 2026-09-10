package org.hongxi.jaws.sample.harbor;

import org.hongxi.jaws.harbor.HarborServer;
import org.hongxi.jaws.rpc.URL;

/**
 * Standalone HarborServer — a Nacos-compatible control plane.
 *
 * <p>Starts a standalone HarborServer that handles service registration,
 * discovery, and configuration management via the Nacos 2.x gRPC protocol.
 * Both {@code jaws-sample-harbor-provider} and {@code jaws-sample-harbor-consumer}
 * point their nacos registry to this server (port 19848).</p>
 *
 * <pre>
 * Usage:
 *   1. Run this main class (HarborServer starts on port 19848)
 *   2. Start HarborProvider / HarborConsumer (they auto-set offset=0)
 * </pre>
 */
public class HarborBootstrap {

    private static final int DEFAULT_PORT = 19848;

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        URL url = new URL("harbor", "0.0.0.0", port, "");
        HarborServer server = new HarborServer(url);
        server.start();

        System.out.println("========================================");
        System.out.println("  HarborServer started on port " + port);
        System.out.println("  Nacos-compatible gRPC control plane");
        System.out.println("========================================");
        System.out.println("Hint: HarborProvider/HarborConsumer auto-set offset=0");
    }
}
