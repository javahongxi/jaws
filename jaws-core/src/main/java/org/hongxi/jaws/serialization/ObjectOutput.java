package org.hongxi.jaws.serialization;

import java.io.Closeable;
import java.io.IOException;

/**
 * Streaming serialization output, analogous to Dubbo's ObjectOutput.
 * <p>
 * Wraps an underlying OutputStream and writes protocol metadata
 * (strings, ints, longs) and business objects directly to the stream,
 * eliminating per-field byte[] allocation.
 * <p>
 * Obtained via {@link Serialization#serialize(java.io.OutputStream)}.
 *
 * @author shenhongxi
 * @since 2026-08-21
 */
public interface ObjectOutput extends Closeable {

    void writeUTF(String value) throws IOException;

    void writeInt(int value) throws IOException;

    void writeLong(long value) throws IOException;

    void writeObject(Object obj) throws IOException;

    void flush() throws IOException;

    void close() throws IOException;
}
