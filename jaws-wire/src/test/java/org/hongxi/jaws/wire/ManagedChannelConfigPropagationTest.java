package org.hongxi.jaws.wire;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks {@link ManagedChannel}'s P0 config pass-through: the builder's keepalive,
 * retry and TLS settings must land verbatim on every backend {@link WireClient}'s
 * {@link URL}, so the transport reads them. Before P0, {@code openClient} only
 * forwarded timeouts and compression, leaving the rest at transport defaults with
 * no way to configure them through the channel.
 * <p>
 * Backends are plain listening {@link ServerSocket}s: {@code WireClient.open()}
 * only needs the TCP connect to succeed (no HTTP/2 or TLS exchange is required to
 * observe the URL params the channel handed down).
 *
 * @author shenhongxi
 */
class ManagedChannelConfigPropagationTest {

    private static String param(URL url, UrlParam.Def<?> def) {
        return url.getParameter(def);
    }

    @Test
    void keepAliveAndRetryAndCoreSettingsPropagate() throws IOException {
        try (ServerSocket ss = new ServerSocket(0);
             ManagedChannel ch = ManagedChannel.builder()
                     .addAddress("127.0.0.1:" + ss.getLocalPort())
                     .requestTimeout(7000)
                     .connectTimeout(1500)
                     .maxInboundMessageSize(2 * 1024 * 1024)
                     .compression("gzip")
                     .keepAlive(15000, 5000)
                     .retry(4, 250, 3000, 150, 10)
                     .roundRobin().build()) {

            URL url = ch.currentClients().get(0).getUrl();
            assertEquals("7000", param(url, UrlParam.Transport.REQUEST_TIMEOUT));
            assertEquals("1500", param(url, UrlParam.Transport.CONNECT_TIMEOUT));
            assertEquals("2097152", param(url, UrlParam.Transport.MAX_INBOUND_MESSAGE_SIZE));
            assertEquals("gzip", param(url, UrlParam.Transport.COMPRESSION));
            assertEquals("15000", param(url, UrlParam.Transport.KEEPALIVE_TIME_MS));
            assertEquals("5000", param(url, UrlParam.Transport.KEEPALIVE_TIMEOUT_MS));
            assertEquals("4", param(url, UrlParam.Transport.RETRY_MAX_ATTEMPTS));
            assertEquals("250", param(url, UrlParam.Transport.RETRY_INITIAL_BACKOFF_MS));
            assertEquals("3000", param(url, UrlParam.Transport.RETRY_MAX_BACKOFF_MS));
            assertEquals("150", param(url, UrlParam.Transport.RETRY_BACKOFF_MULTIPLIER_PCT));
            assertEquals("10", param(url, UrlParam.Transport.RETRY_JITTER_PCT));
        }
    }

    @Test
    void defaultsPreservePriorBehaviour() throws IOException {
        // Without explicit keepalive/retry/TLS the channel must write the same
        // values the transport would fall back to, so upgrading P0 changes nothing
        // observable: keepalive stays off, retry stays at 2 attempts, plaintext h2c.
        try (ServerSocket ss = new ServerSocket(0);
             ManagedChannel ch = ManagedChannel.builder()
                     .addAddress("127.0.0.1:" + ss.getLocalPort()).build()) {

            URL url = ch.currentClients().get(0).getUrl();
            assertEquals("0", param(url, UrlParam.Transport.KEEPALIVE_TIME_MS));
            assertEquals("2", param(url, UrlParam.Transport.RETRY_MAX_ATTEMPTS));
            assertEquals("", param(url, UrlParam.Transport.SSL_TRUST_CERT));
        }
    }

    @Test
    void tlsStaysDisabledByDefault() throws IOException {
        // No TLS material configured → the empty cert params keep the transport on
        // plaintext h2c (buildSslContext returns null when trust cert is blank).
        try (ServerSocket ss = new ServerSocket(0);
             ManagedChannel ch = ManagedChannel.builder()
                     .addAddress("127.0.0.1:" + ss.getLocalPort()).build()) {

            URL url = ch.currentClients().get(0).getUrl();
            assertEquals("", param(url, UrlParam.Transport.SSL_TRUST_CERT));
            assertEquals("", param(url, UrlParam.Transport.SSL_CERT_CHAIN));
            assertEquals("", param(url, UrlParam.Transport.SSL_PRIVATE_KEY));
        }
    }

    @Test
    void builderRejectsInvalidConfig() {
        assertThrows(IllegalArgumentException.class,
                () -> ManagedChannel.builder().keepAlive(-1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedChannel.builder().trustCert(null));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedChannel.builder().mutualTls("  ", "/tmp/key.pem"));
    }
}
