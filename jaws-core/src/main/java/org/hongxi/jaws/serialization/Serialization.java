package org.hongxi.jaws.serialization;

import org.hongxi.jaws.common.extension.Spi;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Streaming serialization strategy interface (SPI, Singleton, ThreadSafe).
 * <p>
 * Unlike a simple {@code byte[] <-> Object} converter, this interface returns
 * streaming {@link ObjectOutput} / {@link ObjectInput} instances that write
 * directly to / read from the underlying IO stream. This eliminates per-field
 * byte[] allocation in the codec layer, aligning with the design used by
 * mature RPC frameworks such as Dubbo.
 * <p>
 * Usage:
 * <pre>
 *   // encode
 *   ObjectOutput out = serialization.serialize(outputStream);
 *   out.writeUTF("methodName");
 *   out.writeObject(arg);
 *   out.flush();
 *
 *   // decode
 *   ObjectInput in = serialization.deserialize(inputStream);
 *   String method = in.readUTF();
 *   Object arg = in.readObject();
 * </pre>
 *
 * @author shenhongxi
 * @since 2020/7/25
 */
@Spi(singleton = true)
public interface Serialization {

    /**
     * Creates a streaming output that writes serialized data directly
     * to the given output stream.
     *
     * @param output the underlying output stream
     * @return an ObjectOutput for streaming serialization
     * @throws IOException if creating the output fails
     */
    ObjectOutput serialize(OutputStream output) throws IOException;

    /**
     * Creates a streaming input that reads serialized data directly
     * from the given input stream.
     *
     * @param input the underlying input stream
     * @return an ObjectInput for streaming deserialization
     * @throws IOException if creating the input fails
     */
    ObjectInput deserialize(InputStream input) throws IOException;

    /**
     * Returns the unique identifier for this serialization, used in the protocol header
     * to specify the serialization method per message.
     * <p>
     * The identifier is stored in the high 5 bits of the flag byte in the Jaws protocol header,
     * so the value must be in the range 0-31 (max 32 serialization types).
     *
     * @return serialization number (0-31)
     */
    byte getSerializationNumber();
}
