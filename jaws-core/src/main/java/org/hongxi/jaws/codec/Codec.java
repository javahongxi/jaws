package org.hongxi.jaws.codec;

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
 *   <li>{@link #encode(Channel, Object)} is invoked by {@code NettyChannel}
 *       when an outgoing RPC {@code Request} or {@code Response} object needs
 *       to be serialized into bytes before being handed to the Netty pipeline
 *       for writing to the socket.</li>
 *   <li>{@link #decode(Channel, byte[])} is invoked by {@code NettyChannelHandler}
 *       after a complete frame has been read from the socket and needs to be
 *       deserialized back into an RPC {@code Request} or {@code Response}
 *       object for further processing by the message handler.</li>
 * </ul>
 *
 * @author shenhongxi
 * @since 2020-06-25
 * @see org.hongxi.jaws.protocol.jaws.JawsCodec
 */
@Spi(scope = Scope.PROTOTYPE)
public interface Codec {

    /**
     * Encodes the given message object into a byte array for network transmission.
     *
     * <p>Called by {@code NettyChannel} when an outgoing {@code Request} or
     * {@code Response} object is ready to be sent over the channel. The
     * implementation should serialize the message (including headers, body,
     * and attachments) into the Jaws wire format or any other protocol-specific
     * binary representation. The resulting byte array is then passed into the
     * Netty pipeline (via {@code NettyEncoder}) for actual socket write.
     *
     * @param channel the channel through which the message will be sent;
     *                provides access to the URL and transport-level metadata
     * @param message the message object to encode (typically a {@code Request}
     *                or {@code Response})
     * @return the encoded byte array ready to be written to the socket
     * @throws IOException if serialization fails
     */
    byte[] encode(Channel channel, Object message) throws IOException;

    /**
     * Decodes a byte array received from the network back into a message object.
     *
     * <p>Called by {@code NettyChannelHandler} after a complete frame has been
     * read from the channel. The implementation should parse the binary data
     * according to the protocol format and reconstruct the original
     * {@code Request} or {@code Response} object for the message handler
     * to dispatch.
     *
     * @param channel the channel from which the data was received;
     *                provides access to the URL and transport-level metadata
     * @param data    the raw byte array representing a complete protocol frame
     * @return the decoded message object (typically a {@code Request} or
     *         {@code Response})
     * @throws IOException if deserialization fails
     */
    Object decode(Channel channel, byte[] data) throws IOException;
}
