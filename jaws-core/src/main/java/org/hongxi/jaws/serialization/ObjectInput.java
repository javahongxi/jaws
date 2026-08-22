package org.hongxi.jaws.serialization;

import java.io.Closeable;
import java.io.IOException;

/**
 * Streaming deserialization input, analogous to Dubbo's ObjectInput.
 * <p>
 * Wraps an underlying InputStream and reads protocol metadata
 * (strings, ints, longs) and business objects directly from the stream,
 * eliminating per-field byte[] allocation.
 * <p>
 * Obtained via {@link Serialization#deserialize(java.io.InputStream)}.
 *
 * @author shenhongxi
 * @since 2026-08-21
 */
public interface ObjectInput extends Closeable {

    String readUTF() throws IOException;

    int readInt() throws IOException;

    long readLong() throws IOException;

    Object readObject() throws IOException, ClassNotFoundException;

    <T> T readObject(Class<T> clazz) throws IOException, ClassNotFoundException;

    void close() throws IOException;
}
