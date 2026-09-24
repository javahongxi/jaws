package org.hongxi.jaws.harbor;

import org.hongxi.jaws.rpc.URL;

/**
 * Shared test fixture: starts a {@link HarborServer} on a free port, retrying
 * when the bind loses the race. CI flake guard — between {@code freePort()}
 * releasing the probe socket and the actual bind, the port can be grabbed as a
 * local ephemeral port by another test's outbound connection, and the bind then
 * fails with "Failed to start WireServer server".
 *
 * @author shenhongxi
 */
final class HarborTestServerSupport {

    private HarborTestServerSupport() {
    }

    /** Starts a single-node HarborServer on a free port, retrying on bind races. */
    static HarborServer startOnFreePort() throws Exception {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            HarborServer server =
                    new HarborServer(new URL("harbor", "0.0.0.0", freePort(), ""));
            try {
                server.start();
                return server;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw last;
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
