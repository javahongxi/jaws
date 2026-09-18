package org.hongxi.jaws.wire;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which stream exceptions are only the sound of a stream going away.
 * <p>
 * The point of the narrow rule is the pair it must separate: a flow-control frame
 * that the HTTP/2 codec queued onto a stream whose encoder has already been removed
 * is noise, while the same exception class raised by a genuinely wrong handler
 * wiring is a bug that has to stay visible.
 *
 * @author shenhongxi
 */
class WireStreamDisconnectTest {

    /** Verbatim shape observed after a connect-reset: codec write, encoder gone. */
    private static final UnsupportedOperationException WINDOW_UPDATE_AFTER_TEARDOWN =
            new UnsupportedOperationException("unsupported message type: "
                    + "DefaultHttp2WindowUpdateFrame (expected: ByteBuf, FileRegion)");

    @Test
    void abruptClientDisconnectIsQuiet() {
        assertTrue(WireStreamServerHandler.isExpectedDisconnect(
                new IOException("Connection reset by peer")));
        assertTrue(WireStreamServerHandler.isExpectedDisconnect(
                new RuntimeException("write failed", new IOException("Broken pipe"))));
    }

    @Test
    void flowControlFrameRacingTeardownIsQuiet() {
        assertTrue(WireStreamServerHandler.isExpectedDisconnect(WINDOW_UPDATE_AFTER_TEARDOWN));
    }

    @Test
    void aRealUnsupportedOperationStillEscalates() {
        assertFalse(WireStreamServerHandler.isExpectedDisconnect(
                new UnsupportedOperationException("Unknown method: bidiStream")));
        assertFalse(WireStreamServerHandler.isExpectedDisconnect(
                new UnsupportedOperationException("Not a bidirectional streaming method")));
    }

    @Test
    void unexpectedFaultsEscalate() {
        assertFalse(WireStreamServerHandler.isExpectedDisconnect(
                new NullPointerException("handler state was never initialised")));
        assertFalse(WireStreamServerHandler.isExpectedDisconnect(
                new IllegalStateException("frame encoder removed")));
    }
}
