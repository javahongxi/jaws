package org.hongxi.jaws.transport.adaptive;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;

import java.net.SocketAddress;

/**
 * A guest protocol on the adaptive port.
 * <p>
 * The point of this interface is the host's ignorance: the detection handler
 * iterates an ordered list of protocols and asks each to claim the connection —
 * it knows <em>that</em> several protocols share the port, never <em>what</em>
 * any of them looks like. All protocol-specific knowledge (magic bytes, the
 * pipeline behind them) lives in the implementation that brought it. Adding a
 * guest — or letting an out-of-tree module register one — adds a list entry,
 * not a branch in the detector.
 *
 * @author shenhongxi
 */
public interface AdaptiveProtocol {

    /** Verdict of a probe against the buffered opening bytes of a connection. */
    enum Result {
        /** This protocol claims the connection. */
        MATCH,
        /** Not ours; the next candidate may still claim it. */
        NO,
        /** Undecisive so far; pause the scan and wait for more bytes. */
        NEED_MORE
    }

    /**
     * @return human-readable label used in the detection log line
     */
    String name();

    /**
     * Probe the buffered opening bytes without consuming them.
     * <p>
     * {@link Result#NEED_MORE} must be returned whenever the bytes seen so far
     * are a genuine prefix of this protocol's signature — the scan pauses on it
     * so an ambiguous opening cannot be grabbed by a candidate it would later
     * fall through to.
     *
     * @param in     the cumulated opening bytes (reader index at the first byte)
     * @param remote the peer address, for refusal messages
     * @return the verdict
     */
    Result detect(ByteBuf in, SocketAddress remote);

    /**
     * Install this protocol's handlers into the pipeline. Called once after
     * {@link #detect} returned {@link Result#MATCH}; must not consume the
     * buffered bytes — the detector replays them through the new handlers.
     */
    void install(ChannelPipeline pipeline);
}
