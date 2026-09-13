package org.hongxi.jaws.transport.http2;

import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the contract that let two independent teardown paths share one named
 * handler without turning an expected client disconnect into a logged failure:
 * a live Harbor cluster produced {@code "NoSuchElementException: http2_codec"}
 * thrown from inside {@code exceptionCaught} when a GOAWAY handler removed the
 * codec first and the connection reset that followed removed it again — because
 * Netty's {@code ChannelPipeline.remove(String)} throws on an absent name.
 */
class Http2PipelineSupportTest {

    private static final String NAME = Http2PipelineSupport.HTTP2_CODEC;

    /** Stand-in for the codec: any named handler will do. */
    private static ChannelInboundHandlerAdapter placeholder() {
        return new ChannelInboundHandlerAdapter();
    }

    @Test
    void removesAPresentHandlerAndReportsIt() {
        EmbeddedChannel ch = new EmbeddedChannel(placeholder());
        ch.pipeline().addFirst(NAME, placeholder());
        assertNotNull(ch.pipeline().get(NAME), "precondition: handler installed under the name");

        assertTrue(Http2PipelineSupport.removeIfExists(ch.pipeline(), NAME),
                "a present handler must be reported as removed");
        assertNull(ch.pipeline().get(NAME), "the handler must actually be gone");

        ch.finish();
    }

    @Test
    void absentNameIsNotAnException() {
        EmbeddedChannel ch = new EmbeddedChannel(placeholder());

        // The guard under test: Netty's own remove(String) throws, which is what
        // surfaced as a Netty warning about a user handler's exceptionCaught.
        assertThrows(NoSuchElementException.class, () -> ch.pipeline().remove(NAME),
                "precondition: the raw call does throw for an absent name");
        assertFalse(Http2PipelineSupport.removeIfExists(ch.pipeline(), NAME),
                "an already-removed handler must be a no-op returning false, not a throw");

        ch.finish();
    }

    @Test
    void secondTeardownPathIsIdempotent() {
        EmbeddedChannel ch = new EmbeddedChannel(placeholder());
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addFirst(NAME, placeholder());

        assertTrue(Http2PipelineSupport.removeIfExists(pipeline, NAME),
                "first teardown path (e.g. a GOAWAY handler) removes it");
        assertDoesNotThrow(() -> Http2PipelineSupport.removeIfExists(pipeline, NAME),
                "second teardown path (e.g. the exception handler reacting to the reset "
                        + "that follows) must not throw out of exceptionCaught");
        assertFalse(Http2PipelineSupport.removeIfExists(pipeline, NAME));

        ch.finish();
    }
}
