package org.hongxi.jaws.wire;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.stream.StreamSource;
import org.hongxi.jaws.transport.MessageHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the warning a wire transport emits when it is handed a parameter it
 * does not act on.
 * <p>
 * {@code heartbeat}, {@code sendReconnect} and {@code maxContentLength} are
 * read by the binary and adaptive transports only. Before this, setting them on
 * a wire endpoint was silently accepted, which is worse than rejecting them: the
 * operator believes a ceiling or a keepalive is in place. The warning is the
 * minimum honest answer until someone implements them for HTTP/2.
 *
 * @author shenhongxi
 */
class WireIgnoredParametersTest {

    private static final MessageHandler UNUSED = new MessageHandler() {
        @Override
        public CompletableFuture<Object> handleAsync(Object message) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public StreamSource<Object> handleStream(Request request, StreamSource<Object> in) {
            throw new UnsupportedOperationException();
        }
    };

    @Test
    void theClientWarnsAboutEachParameterItIgnores() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Map<String, String> params = new HashMap<>();
        params.put("heartbeat", "30000");
        params.put("sendReconnect", "false");
        params.put("maxContentLength", "8388608");

        List<ILoggingEvent> events = captureFrom(WireClient.class, () ->
                new WireClient(new URL("wire", "127.0.0.1", port, "svc/demo", params)).close());

        List<String> warnings = warnedAbout(events);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("heartbeat")),
                "heartbeat must be called out, got " + warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("sendReconnect")),
                "sendReconnect must be called out, got " + warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("maxContentLength")),
                "maxContentLength must be called out, got " + warnings);
    }

    @Test
    void theServerWarnsTooAndStaysQuietWithoutTheParameters() throws Exception {
        int port = freePort();
        int otherPort = freePort();
        Map<String, String> params = new HashMap<>();
        params.put("heartbeat", "30000");

        List<ILoggingEvent> warned = captureFrom(WireServer.class, () ->
                new WireServer(new URL("wire", "127.0.0.1", port, "", params), UNUSED).close());
        assertTrue(warnedAbout(warned).stream().anyMatch(w -> w.contains("heartbeat")),
                "a wire server handed heartbeat must say it ignores it");

        List<ILoggingEvent> silent = captureFrom(WireServer.class, () ->
                new WireServer(new URL("wire", "127.0.0.1", otherPort, "", new HashMap<>()),
                        UNUSED).close());
        assertEquals(0, warnedAbout(silent).size(),
                "no dead parameter, no warning — otherwise the log is noise");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static List<String> warnedAbout(List<ILoggingEvent> events) {
        return events.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static List<ILoggingEvent> captureFrom(Class<?> owner, Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(owner);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
            return appender.list;
        } finally {
            logger.detachAppender(appender);
        }
    }
}
