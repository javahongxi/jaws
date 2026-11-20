package org.hongxi.jaws.harbor;

import org.hongxi.jaws.rpc.URL;

/**
 * Standalone HarborServer — a Nacos-compatible service registry.
 *
 * <p>Starts a standalone HarborServer that handles service registration and
 * discovery via the Nacos 2.x gRPC protocol.</p>
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
 *
 *   Bind host defaults to 127.0.0.1 and must literally match this node's entry
 *   in the cluster list (raft self-exclusion compares host:port strings).
 *   For a cross-host cluster, bind the advertised address:
 *     java -Dharbor.host=10.0.0.1 ...HarborBootstrap 19848 --cluster 10.0.0.1:19848,10.0.0.2:19848,10.0.0.3:19848
 *
 *   Raft durability directory for persistent instances
 *   (default ~/.harbor/data, each node under raft-<port>):
 *     java ...HarborBootstrap 19848 --data-dir /var/harbor
 * </pre>
 *
 * @author shenhongxi
 */
public class HarborBootstrap {

    private static final int DEFAULT_PORT = 19848;

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        String clusterMembers = System.getProperty("harbor.clusterMembers", "");
        String dataDir = System.getProperty("harbor.dataDir", "");

        // Parse args: [port] [--cluster host1:port1,host2:port2] [--data-dir path]
        for (int i = 0; i < args.length; i++) {
            if ("--cluster".equals(args[i]) && i + 1 < args.length) {
                clusterMembers = args[++i];
            } else if ("--data-dir".equals(args[i]) && i + 1 < args.length) {
                dataDir = args[++i];
            } else if (i == 0) {
                port = Integer.parseInt(args[0]);
            }
        }

        // Bind loopback by default: ClusterManager derives the raft/dist self
        // address from this host, and it must literally match the entry in
        // --cluster (the sample script lists 127.0.0.1). A 0.0.0.0 bind would
        // resolve self to the LAN IP, fail self-exclusion, and leave every node
        // treating its own loopback entry as a phantom fourth raft peer.
        String host = System.getProperty("harbor.host", "127.0.0.1");
        URL url = new URL("harbor", host, port, "");
        if (!clusterMembers.isEmpty()) {
            url.addParameter(HarborServer.PARAM_CLUSTER_MEMBERS, clusterMembers);
        }
        if (!dataDir.isEmpty()) {
            url.addParameter(HarborServer.PARAM_DATA_DIR, dataDir);
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
        System.out.println("  Nacos-compatible gRPC registry");
        System.out.println("  Raft data dir: " + server.getRaftDataDir());
        System.out.println("  Management API: http://localhost:" + (port + 10) + "/api/*");
        System.out.println("========================================");
    }
}
