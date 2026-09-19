package org.hongxi.jaws.wire;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice 3 of P2: the aggregate connectivity view of {@link ManagedChannel}
 * ({@code getState} / {@code notifyWhenStateChanged}), derived from each backend's
 * own {@link WireConnectivityTracker}. Backends open over plain {@link ServerSocket}s
 * — {@code WireClient.open()} drives a TCP connect and marks the connection READY,
 * which is all the aggregate reads.
 *
 * @author shenhongxi
 */
class ManagedChannelConnectivityTest {

    @Test
    void freshChannelIsReady() throws IOException {
        try (ServerSocket a = new ServerSocket(0);
             ServerSocket b = new ServerSocket(0);
             ManagedChannel ch = ManagedChannel.builder()
                     .addAddress("127.0.0.1:" + a.getLocalPort())
                     .addAddress("127.0.0.1:" + b.getLocalPort())
                     .roundRobin().build()) {

            assertEquals(WireConnectivityState.READY, ch.getState(),
                    "two connected backends ⇒ channel READY");
            // requestConnection is a safe no-op on a live (non-IDLE) channel.
            assertEquals(WireConnectivityState.READY, ch.getState(true));
        }
    }

    @Test
    void stateChangeCallbackFiresOnShutdownAndOnlyOnce() throws IOException, InterruptedException {
        try (ServerSocket ss = new ServerSocket(0)) {
            ManagedChannel ch = ManagedChannel.builder()
                    .addAddress("127.0.0.1:" + ss.getLocalPort()).build();

            AtomicInteger fired = new AtomicInteger();
            ch.notifyWhenStateChanged(WireConnectivityState.READY, fired::incrementAndGet);
            assertEquals(0, fired.get(), "still READY → callback must not have fired");

            ch.shutdownNow();   // drives the aggregate to SHUTDOWN
            assertEquals(WireConnectivityState.SHUTDOWN, ch.getState());
            assertEquals(1, fired.get(), "callback fires exactly once when leaving READY");

            // A second state change must not re-fire the (self-deregistering) one-shot.
            ch.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
            ch.getState();
            assertEquals(1, fired.get(), "one-shot listener deregisters after firing");
        }
    }

    @Test
    void callbackForNonCurrentStateRunsImmediately() throws IOException {
        try (ServerSocket ss = new ServerSocket(0);
             ManagedChannel ch = ManagedChannel.builder()
                     .addAddress("127.0.0.1:" + ss.getLocalPort()).build()) {

            AtomicInteger fired = new AtomicInteger();
            // Watching to leave TRANSIENT_FAILURE, but we're READY → differs now.
            ch.notifyWhenStateChanged(WireConnectivityState.TRANSIENT_FAILURE, fired::incrementAndGet);
            assertEquals(1, fired.get(), "already differs from source → fires immediately");
        }
    }
}
