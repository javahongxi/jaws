package org.hongxi.jaws.transport.http2;

import io.netty.channel.ChannelPipeline;

/**
 * Small pipeline helpers shared by the HTTP/2 transports.
 *
 * @author shenhongxi
 */
public final class Http2PipelineSupport {

    /**
     * The name {@link io.netty.handler.codec.http2.Http2FrameCodec} is installed
     * under by both {@link AbstractHttp2Server} and {@link AbstractHttp2Client},
     * and the handler several teardown paths try to remove.
     */
    public static final String HTTP2_CODEC = "http2_codec";

    private Http2PipelineSupport() {
    }

    /**
     * Remove a named handler only if it is still in the pipeline.
     * <p>
     * Netty's {@link ChannelPipeline#remove(String)} throws
     * {@link java.util.NoSuchElementException} when the name is absent, so a second
     * teardown path that also removes the same handler turns an expected client
     * disconnect into a logged failure from inside {@code exceptionCaught} — Netty
     * reports it as {@code "An exception 'NoSuchElementException: http2_codec' was
     * thrown by a user handler's exceptionCaught() method"}, which buries the real
     * (harmless) reset. Removal is check-then-act, and every caller here runs on the
     * connection's own event-loop thread, so the check cannot race.
     *
     * @return {@code true} if the handler was present and removed
     */
    public static boolean removeIfExists(ChannelPipeline pipeline, String name) {
        if (pipeline.get(name) == null) {
            return false;
        }
        pipeline.remove(name);
        return true;
    }
}
