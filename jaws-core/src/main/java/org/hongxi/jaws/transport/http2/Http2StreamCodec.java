package org.hongxi.jaws.transport.http2;

import org.hongxi.jaws.serialization.ObjectInput;
import org.hongxi.jaws.serialization.ObjectOutput;
import org.hongxi.jaws.serialization.Serialization;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Codec for encoding and decoding individual stream items in HTTP/2 streaming calls.
 * <p>
 * Each stream item is serialized independently using the configured {@link Serialization}.
 * Unlike the unary codec which wraps the entire Request/Response, this codec handles
 * individual data elements within a stream.
 * <p>
 * Wire format per item: the serialized bytes of the object written via
 * {@link ObjectOutput#writeObject(Object)}.
 *
 * @author shenhongxi
 */
public final class Http2StreamCodec {

    private Http2StreamCodec() {
    }

    /**
     * Serialize a single stream item to bytes.
     *
     * @param item          the item to serialize (may be any object type)
     * @param serialization the serialization to use
     * @return the serialized bytes
     */
    public static byte[] encodeItem(Object item, Serialization serialization) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutput out = serialization.serialize(bos)) {
            out.writeObject(item);
            out.flush();
        }
        return bos.toByteArray();
    }

    /**
     * Deserialize a single stream item from bytes.
     *
     * @param data          the serialized bytes
     * @param serialization the serialization to use
     * @param itemType      the expected type of the item (may be null for Object)
     * @return the deserialized item
     */
    public static Object decodeItem(byte[] data, Serialization serialization, Class<?> itemType)
            throws IOException, ClassNotFoundException {
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        try (ObjectInput in = serialization.deserialize(bis)) {
            return in.readObject();
        }
    }

    /**
     * Deserialize a single stream item from bytes, returning Object.
     *
     * @param data          the serialized bytes
     * @param serialization the serialization to use
     * @return the deserialized item
     */
    public static Object decodeItem(byte[] data, Serialization serialization)
            throws IOException, ClassNotFoundException {
        return decodeItem(data, serialization, null);
    }
}
