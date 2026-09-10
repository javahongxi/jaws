package org.hongxi.jaws.harbor;

import org.hongxi.jaws.rpc.URL;

/**
 * Standalone HarborServer — a Nacos-compatible control plane.
 *
 * <p>Starts a standalone HarborServer that handles service registration,
 * discovery, and configuration management via the Nacos 2.x gRPC protocol.</p>
 *
 * <pre>
 * Usage:
 *   Single node:
 *     java org.hongxi.jaws.harbor.HarborBootstrap [port]
 *
 *   3-node cluster (each node specifies the other two as peers):
 *     Node1: java ...HarborBootstrap 19848 --cluster 10.0.0.2:19848,10.0.0.3:19848
 *     Node2: java ...HarborBootstrap 19848 --cluster 10.0.0.1:19848,10.0.0.3:19848
 *     Node3: java ...HarborBootstrap 19848 --cluster 10.0.0.1:19848,10.0.0.2:19848
 *
 *   Or via system property:
 *     java -Dharbor.clusterMembers=10.0.0.2:19848,10.0.0.3:19848 ...HarborBootstrap 19848
 * </pre>
 *
 * @author shenhongxi
 */
public class HarborBootstrap {

    private static final int DEFAULT_PORT = 19848;

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        String clusterMembers = System.getProperty("harbor.clusterMembers", "");

        // Parse args: [port] [--cluster host1:port1,host2:port2]
        for (int i = 0; i < args.length; i++) {
            if ("--cluster".equals(args[i]) && i + 1 < args.length) {
                clusterMembers = args[++i];
            } else if (i == 0) {
                port = Integer.parseInt(args[0]);
            }
        }

        URL url = new URL("harbor", "0.0.0.0", port, "");
        if (!clusterMembers.isEmpty()) {
            url.addParameter(HarborServer.PARAM_CLUSTER_MEMBERS, clusterMembers);
        }

        HarborServer server = new HarborServer(url);
        server.start();

        System.out.println("========================================");
        System.out.println("  HarborServer started on port " + port);
        if (!clusterMembers.isEmpty()) {
            System.out.println("  Cluster peers: " + clusterMembers);
        } else {
            System.out.println("  Single-node mode (no cluster peers)");
        }
        System.out.println("  Nacos-compatible gRPC control plane");
        System.out.println("  Management API: http://localhost:" + (port + 10) + "/api/*");
        System.out.println("  Use jaws-sample-admin for management UI: http://localhost:8088");
        System.out.println("========================================");
    }
}
