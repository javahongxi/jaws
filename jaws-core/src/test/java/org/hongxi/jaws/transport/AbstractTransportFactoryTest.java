package org.hongxi.jaws.transport;

import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for shared server and client pooling in {@link AbstractTransportFactory}.
 */
class AbstractTransportFactoryTest {

    private FakeTransportFactory factory;

    @BeforeEach
    void setUp() {
        factory = new FakeTransportFactory();
    }

    @Test
    void createClientSharesSameRemoteAddress() {
        URL demoUrl = new URL("jaws", "127.0.0.1", 10000, "org.example.DemoService");
        URL orderUrl = new URL("jaws", "127.0.0.1", 10000, "org.example.OrderService");

        Client first = factory.createClient(demoUrl);
        Client second = factory.createClient(orderUrl);

        assertSame(first, second, "clients targeting the same host:port must be shared");
        assertTrue(factory.createdClients() == 1, "only one underlying client should be created");
    }

    @Test
    void createClientDoesNotShareDifferentAddress() {
        Client first = factory.createClient(new URL("jaws", "127.0.0.1", 10000, "org.example.DemoService"));
        Client second = factory.createClient(new URL("jaws", "127.0.0.1", 10001, "org.example.DemoService"));

        assertNotSame(first, second);
        assertTrue(factory.createdClients() == 2);
    }

    @Test
    void releaseClientClosesOnlyWhenLastReferenceReleased() {
        URL url = new URL("jaws", "127.0.0.1", 10000, "org.example.DemoService");
        Client first = factory.createClient(url);
        Client second = factory.createClient(new URL("jaws", "127.0.0.1", 10000, "org.example.OrderService"));

        factory.releaseClient(first);
        assertFalse(((FakeClient) second).isClosed(), "shared client must stay open while referenced");

        factory.releaseClient(second);
        assertTrue(((FakeClient) second).isClosed(), "shared client must close when the last reference is released");
    }

    @Test
    void createClientAfterLastReleaseCreatesNewClient() {
        URL url = new URL("jaws", "127.0.0.1", 10000, "org.example.DemoService");
        Client first = factory.createClient(url);
        factory.releaseClient(first);

        Client second = factory.createClient(url);
        assertNotSame(first, second, "a new client must be created after the shared one was closed");
        assertTrue(factory.createdClients() == 2);
    }

    @Test
    void createServerSharesSameHostPort() {
        URL demoUrl = new URL("jaws", "127.0.0.1", 10000, "org.example.DemoService");
        URL orderUrl = new URL("jaws", "127.0.0.1", 10000, "org.example.OrderService");

        Server first = factory.createServer(demoUrl, null);
        Server second = factory.createServer(orderUrl, null);

        assertSame(first, second, "services on the same host:port must share one server");
        assertTrue(factory.createdServers() == 1, "only one underlying server should be created");
    }

    @Test
    void releaseServerClosesOnlyWhenLastReferenceReleased() {
        URL url = new URL("jaws", "127.0.0.1", 10000, "org.example.DemoService");
        Server first = factory.createServer(url, null);
        Server second = factory.createServer(new URL("jaws", "127.0.0.1", 10000, "org.example.OrderService"), null);

        factory.releaseServer(first);
        assertFalse(((FakeServer) second).isClosed(), "shared server must stay open while referenced");

        factory.releaseServer(second);
        assertTrue(((FakeServer) second).isClosed(), "shared server must close when the last reference is released");
    }

    @Test
    void createServerAfterLastReleaseCreatesNewServer() {
        URL url = new URL("jaws", "127.0.0.1", 10000, "org.example.DemoService");
        Server first = factory.createServer(url, null);
        factory.releaseServer(first);

        Server second = factory.createServer(url, null);
        assertNotSame(first, second, "a new server must be created after the shared one was closed");
        assertTrue(factory.createdServers() == 2);
    }

    static class FakeTransportFactory extends AbstractTransportFactory {
        private int created;
        private int createdServers;

        int createdClients() {
            return created;
        }

        int createdServers() {
            return createdServers;
        }

        @Override
        protected Server innerCreateServer(URL url, MessageHandler messageHandler) {
            createdServers++;
            return new FakeServer(url);
        }

        @Override
        protected Client innerCreateClient(URL url) {
            created++;
            return new FakeClient(url);
        }
    }

    static class FakeServer implements Server {
        private final URL url;
        private boolean closed;

        FakeServer(URL url) {
            this.url = url;
        }

        boolean isClosed() {
            return closed;
        }

        @Override
        public boolean open() {
            return true;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public void close(int timeout) {
            closed = true;
        }

        @Override
        public boolean isAvailable() {
            return !closed;
        }

        @Override
        public URL getUrl() {
            return url;
        }
    }

    static class FakeClient implements Client {
        private final URL url;
        private boolean closed;

        FakeClient(URL url) {
            this.url = url;
        }

        boolean isClosed() {
            return closed;
        }

        @Override
        public Response request(Request request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean open() {
            return true;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public void close(int timeout) {
            closed = true;
        }

        @Override
        public boolean isAvailable() {
            return !closed;
        }

        @Override
        public URL getUrl() {
            return url;
        }
    }
}
