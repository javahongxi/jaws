package org.hongxi.jaws.serialization;

import org.apache.fury.Fury;
import org.apache.fury.ThreadSafeFury;
import org.hongxi.jaws.common.extension.Extension;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Apache Fury-based serialization implementation.
 * <p>
 * Fury is a high-performance serialization framework that uses JIT code generation
 * to achieve near-direct-call performance. Since Fury is byte-array-oriented rather
 * than streaming, the ObjectOutput / ObjectInput use a hybrid approach: primitive
 * types (UTF, int, long) are written via {@link DataOutputStream} / {@link DataInputStream},
 * while objects are serialized as a 4-byte length prefix followed by Fury's binary payload
 * ({@code -1} indicates null).
 * <p>
 * Fury natively handles POJOs, collections, maps, records, and exceptions without
 * requiring {@link java.io.Serializable}. Class registration is disabled to support
 * arbitrary types in RPC scenarios.
 * <p>
 * Limitations compared with hessian2:
 * <ul>
 *   <li>Not a true streaming format — each object is buffered into a {@code byte[]}
 *       before being written to the underlying stream;</li>
 *   <li>Fury's wire format is version-sensitive; both sides should use the same
 *       Fury version for compatibility.</li>
 * </ul>
 *
 * @author shenhongxi
 * @since 2026-09-07
 */
@Extension(value = "fury", number = 3)
public class FurySerialization implements Serialization {

    private static final ThreadSafeFury FURY = Fury.builder()
            .requireClassRegistration(false)
            .buildThreadSafeFury();

    @Override
    public ObjectOutput serialize(OutputStream output) throws IOException {
        return new FuryObjectOutput(output);
    }

    @Override
    public ObjectInput deserialize(InputStream input) throws IOException {
        return new FuryObjectInput(input);
    }

    @Override
    public byte getSerializationNumber() {
        return 3;
    }

    // ---- streaming output (DataOutputStream for primitives, length-prefixed Fury for objects) ----

    private static class FuryObjectOutput implements ObjectOutput {
        private final DataOutputStream dos;

        FuryObjectOutput(OutputStream os) {
            this.dos = new DataOutputStream(os);
        }

        @Override
        public void writeUTF(String value) throws IOException {
            dos.writeUTF(value);
        }

        @Override
        public void writeInt(int value) throws IOException {
            dos.writeInt(value);
        }

        @Override
        public void writeLong(long value) throws IOException {
            dos.writeLong(value);
        }

        @Override
        public void writeObject(Object obj) throws IOException {
            if (obj == null) {
                dos.writeInt(-1);
                return;
            }
            byte[] bytes = FURY.serialize(obj);
            dos.writeInt(bytes.length);
            dos.write(bytes);
        }

        @Override
        public void flush() throws IOException {
            dos.flush();
        }

        @Override
        public void close() throws IOException {
            dos.close();
        }
    }

    // ---- streaming input (DataInputStream for primitives, length-prefixed Fury for objects) ----

    private static class FuryObjectInput implements ObjectInput {
        private final DataInputStream dis;

        FuryObjectInput(InputStream is) {
            this.dis = new DataInputStream(is);
        }

        @Override
        public String readUTF() throws IOException {
            return dis.readUTF();
        }

        @Override
        public int readInt() throws IOException {
            return dis.readInt();
        }

        @Override
        public long readLong() throws IOException {
            return dis.readLong();
        }

        @Override
        public Object readObject() throws IOException {
            int len = dis.readInt();
            if (len < 0) {
                return null;
            }
            byte[] bytes = new byte[len];
            dis.readFully(bytes);
            return FURY.deserialize(bytes);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T readObject(Class<T> clazz) throws IOException {
            int len = dis.readInt();
            if (len < 0) {
                return null;
            }
            byte[] bytes = new byte[len];
            dis.readFully(bytes);
            return (T) FURY.deserialize(bytes);
        }

        @Override
        public void close() throws IOException {
            dis.close();
        }
    }
}
