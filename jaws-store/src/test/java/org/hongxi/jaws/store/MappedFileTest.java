package org.hongxi.jaws.store;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MappedFile}, focused on the shutdown-time interrupt
 * race: {@code MessageStore.shutdown()} interrupts the CQ flush ticker, and an
 * interrupt landing inside {@code FileChannel.force()} closes the channel and
 * throws {@code ClosedByInterruptException} by JDK design. That is an expected
 * exit, not a disk failure, and must not surface as an ERROR.
 */
class MappedFileTest {

    @TempDir
    Path tempDir;

    @Test
    void interruptedFlushIsLoggedAsShutdownNotAsDiskFailure() throws Exception {
        MappedFile mappedFile =
                new MappedFile(tempDir.resolve("00000000000000000000").toString(), 64 * 1024);
        try {
            mappedFile.appendMessage(ByteBuffer.wrap(new byte[128]));

            Logger log = (Logger) LoggerFactory.getLogger(MappedFile.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            log.addAppender(appender);
            Level previous = log.getLevel();
            log.setLevel(Level.DEBUG);
            try {
                // A preset interrupt flag hits the exact code path the ticker
                // hits when shutdown() interrupts it mid-force(): the channel
                // is closed and force() throws ClosedByInterruptException.
                Thread.currentThread().interrupt();
                int flushed = mappedFile.flush();
                Thread.interrupted();   // clear the flag for subsequent IO

                assertEquals(0, flushed,
                        "an interrupted force must not advance the flushed position");
                assertTrue(appender.list.stream().noneMatch(e -> e.getLevel() == Level.ERROR),
                        "a flush interrupted by shutdown must not be logged as a disk failure");
                assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.DEBUG),
                        "the shutdown interrupt should surface at debug level instead");

                // The interrupt-driven close must also be mirrored on the file
                // itself: the final flush of MappedFileQueue.shutdown would
                // otherwise report a ClosedChannelException against the dead
                // channel as an ERROR. A silent no-op means the state is copied.
                int eventsAfterInterrupt = appender.list.size();
                int flushedAgain = mappedFile.flush();
                assertEquals(0, flushedAgain, "a closed file must not pretend to flush");
                assertEquals(eventsAfterInterrupt, appender.list.size(),
                        "flushing an interrupt-closed file must stay silent");
            } finally {
                Thread.interrupted();
                log.detachAppender(appender);
                log.setLevel(previous);
            }
        } finally {
            mappedFile.close();
        }
    }
}
