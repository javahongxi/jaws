package org.hongxi.jaws.registry.zookeeper;

import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression check: when a ZK node carries no readable URL data, the address
 * must be resolved strictly from the host:port node name. Unresolvable node
 * names must yield null (node skipped) instead of a fabricated URL with a
 * guessed port (previously 80) that could misroute traffic.
 */
class ZookeeperRegistryTest {

    @Test
    void validHostPortNodeNameResolves() {
        URL url = ZookeeperRegistry.parseUrlFromNodeName(baseUrl(), "192.168.1.10:20880");

        assertNotNull(url);
        assertEquals("192.168.1.10", url.getHost());
        assertEquals(20880, url.getPort());
    }

    @Test
    void ipv6NodeNameResolvesByLastColon() {
        URL url = ZookeeperRegistry.parseUrlFromNodeName(baseUrl(), "[::1]:20880");

        assertNotNull(url);
        assertEquals("[::1]", url.getHost());
        assertEquals(20880, url.getPort());
    }

    @Test
    void nodeNamesWithoutValidHostPortAreRejected() {
        assertNull(ZookeeperRegistry.parseUrlFromNodeName(baseUrl(), "no-port"));
        assertNull(ZookeeperRegistry.parseUrlFromNodeName(baseUrl(), "host:"));
        assertNull(ZookeeperRegistry.parseUrlFromNodeName(baseUrl(), ":20880"));
        assertNull(ZookeeperRegistry.parseUrlFromNodeName(baseUrl(), "host:abc"));
        assertNull(ZookeeperRegistry.parseUrlFromNodeName(baseUrl(), ""));
    }

    private URL baseUrl() {
        return new URL("jaws", "127.0.0.1", 20880, "org.hongxi.jaws.DemoService", new HashMap<>());
    }
}
