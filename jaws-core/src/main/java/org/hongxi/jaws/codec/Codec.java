package org.hongxi.jaws.codec;

import io.netty.buffer.ByteBuf;
import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;
import org.hongxi.jaws.transport.Channel;

import java.io.IOException;

/**
 * SPI interface for encoding and decoding messages at the transport layer.
 *
 * <p>Implementations are responsible for serializing application-level objects
 * into byte arrays for network transmission, and deserializing received byte
 * arrays back into objects.
 *
 * <p>The codec is selected via the {@code codec} URL parameter and loaded
 * through {@link org.hongxi.jaws.common.extension.ExtensionLoader}. Each
 * lookup returns a new prototype-scoped instance.
 *
 * <p><b>Usage scenarios:</b>
 * <ul>
 *   <li>{@link #encode(Channel, Object, ByteBuf)} is invoked by {@code NettyChannel}
 *       when an outgoing RPC {@code Request} or {@code Response} object needs
 *       to be serialized. The codec writes directly into the provided {@link ByteBuf},
 *       avoiding intermediate byte[] allocation.</li>
 *   <li>{@link #decode(Channel, ByteBuf)} is invoked by {@code NettyChannelHandler}
 *       after a complete frame has been read from the socket. The codec reads
 *       directly from the {@link ByteBuf}, leveraging zero-copy slicing for
 *       the body portion.</li>
 * </ul>
 *
 * @author shenhongxi
 * @since 2020-06-25
 * @see org.hongxi.jaws.protocol.jaws.JawsCodec
 */
@Spi(scope = Scope.PROTOTYPE)
public interface Codec {

    /**
     * Encodes the given message object directly into the provided {@link ByteBuf}.
     *
     * <p>Called by {@code NettyChannel} when an outgoing {@code Request} or
     * {@code Response} object is ready to be sent. The implementation writes
     * the protocol header and serialized body directly into the target buffer,
     * eliminating intermediate byte[] allocation.
     *
     * @param channel the channel through which the message will be sent;
     *                provides access to the URL and transport-level metadata
     * @param message the message object to encode (typically a {@code Request}
     *                or {@code Response})
     * @param out     the target {@link ByteBuf} to write the encoded frame into
     * @throws IOException if serialization fails
     */
    void encode(Channel channel, Object message, ByteBuf out) throws IOException;

    /**
     * Decodes a message object directly from the provided {@link ByteBuf}.
     *
     * <p>Called by {@code NettyChannelHandler} after a complete frame has been
     * read from the channel. The implementation reads the protocol header and
     * deserializes the body directly from the buffer, using zero-copy slicing
     * where possible.
     *
     * @param channel the channel from which the data was received;
     *                provides access to the URL and transport-level metadata
     * @param in      the {@link ByteBuf} containing the complete protocol frame
     * @return the decoded message object (typically a {@code Request} or
     *         {@code Response})
     * @throws IOException if deserialization fails
     */
    Object decode(Channel channel, ByteBuf in) throws IOException;
}
