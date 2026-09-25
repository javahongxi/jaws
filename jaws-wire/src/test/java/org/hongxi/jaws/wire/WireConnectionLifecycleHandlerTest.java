package org.hongxi.jaws.wire;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link WireConnectionLifecycleHandler}: the max-idle GOAWAY that
 * closes a quiet connection, and the one thing that goes wrong when the write
 * itself does not.
 * <p>
 * A failed outbound write never reaches the idle-check task: Netty turns it
 * into a failed promise (and its own warning), so a silently dropped GOAWAY
 * would leave both peers believing the close was announced. The handler is
 * therefore expected to report the promise outcome itself.
 *
 * @author shenhongxi
 */
class WireConnectionLifecycleHandlerTest {

    /** Idle threshold of 1ms; the check itself is driven by the test, not the clock. */
    private static final long MAX_IDLE_MS = 1;

    @Test
    void failedGoAwayWriteIsReported() {
        List<ILoggingEvent> events = driveIdleClose(true);

        assertTrue(events.stream().anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("GOAWAY")),
                "a GOAWAY that failed to reach the wire must leave a warning, got " + events);
    }

    @Test
    void sentGoAwayWriteIsNotReportedAsFailure() {
        // The counterpart that keeps the previous test honest: reporting the
        // outcome is not the same as reporting a failure every tick.
        List<ILoggingEvent> events = driveIdleClose(false);

        assertFalse(events.stream().anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("GOAWAY")),
                "a GOAWAY that went out must not be reported as a failure, got " + events);
    }

    /**
     * Installs the handler on an embedded channel whose outbound either fails
     * the GOAWAY promise or lets it through, runs the captured periodic idle
     * check until it attempts the close, and returns what the handler logged.
     */
    private static List<ILoggingEvent> driveIdleClose(boolean failTheWrite) {
        CapturingScheduler scheduler = new CapturingScheduler();
        GoAwaySpy spy = new GoAwaySpy(failTheWrite);
        Logger logger = (Logger) LoggerFactory.getLogger(WireConnectionLifecycleHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        EmbeddedChannel channel = new EmbeddedChannel(spy,
                new WireConnectionLifecycleHandler(MAX_IDLE_MS, 0, 0, scheduler));
        try {
            Runnable idleCheck = scheduler.periodicTask.get();
            assertNotNull(idleCheck, "the handler must arm the idle check");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!spy.attempted.get() && System.nanoTime() < deadline) {
                idleCheck.run();
            }
            assertTrue(spy.attempted.get(), "the idle check must send the GOAWAY");
            // The handler closes an idle connection from the write listener, so an
            // open channel would mean the outcome never came back at all and the
            // assertions below would be vacuous.
            assertFalse(channel.isOpen(), "the write outcome must reach the listener");
            return appender.list;
        } finally {
            logger.detachAppender(appender);
            channel.finishAndReleaseAll();
        }
    }

    /** Records the periodic task instead of running it, so the test owns the timing. */
    private static final class CapturingScheduler extends AbstractExecutorService
            implements ScheduledExecutorService {
        final AtomicReference<Runnable> periodicTask = new AtomicReference<>();

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay,
                                                      long period, TimeUnit unit) {
            periodicTask.set(command);
            return null;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
                                                         long delay, TimeUnit unit) {
            return null;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return null;
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            return null;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    /** Sits closer to the head than the handler, and can make the GOAWAY fail. */
    private static final class GoAwaySpy extends ChannelOutboundHandlerAdapter {
        final AtomicBoolean attempted = new AtomicBoolean();
        private final boolean failTheWrite;

        GoAwaySpy(boolean failTheWrite) {
            this.failTheWrite = failTheWrite;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
                throws Exception {
            if (msg instanceof Http2GoAwayFrame) {
                attempted.set(true);
                if (failTheWrite) {
                    promise.tryFailure(new IllegalStateException("simulated write failure"));
                    return;
                }
            }
            super.write(ctx, msg, promise);
        }
    }
}
