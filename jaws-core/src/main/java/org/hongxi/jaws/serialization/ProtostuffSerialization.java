package org.hongxi.jaws.serialization;

import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.Schema;
import io.protostuff.runtime.RuntimeSchema;
import org.hongxi.jaws.common.extension.Extension;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Protostuff-based serialization implementation.
 * <p>
 * Protostuff produces the protobuf wire format at runtime via reflection,
 * so no IDL or generated code is required and plain POJOs can be serialized
 * directly. Since Protostuff is byte-array-oriented rather than streaming,
 * the ObjectOutput / ObjectInput use a hybrid approach: primitive types
 * (UTF, int, long) are written via {@link DataOutputStream} / {@link DataInputStream},
 * while objects are written as a length tag followed by their payload:
 * <ul>
 *   <li>{@code tag < 0}: special markers — {@code -1} null, {@code -2} List, {@code -3} Map;
 *       List/Map elements are encoded recursively with the same format;</li>
 *   <li>{@code tag >= 0}: the class name (UTF) followed by {@code tag} protostuff bytes.
 *       Scalar types (String, boxed numbers, ...) are supported natively by
 *       protostuff-runtime schemas.</li>
 * </ul>
 * The embedded class name makes {@link ObjectInput#readObject()} self-describing
 * and supports polymorphic arguments.
 * <p>
 * Limitations compared with hessian2/fastjson2:
 * <ul>
 *   <li>Objects must have an accessible no-arg constructor (records and
 *       constructor-only classes are not supported);</li>
 *   <li>The POJO wire format is field-order-sensitive, not self-describing per
 *       field, so adding/removing fields across versions requires care;</li>
 *   <li>Cyclic references are not supported.</li>
 * </ul>
 *
 * @author shenhongxi
 * @since 2026-08-24
 */
@Extension(value = "protostuff", number = 2)
public class ProtostuffSerialization implements Serialization {

    private static final int TAG_NULL = -1;
    private static final int TAG_LIST = -2;
    private static final int TAG_MAP = -3;

    /** Reusable per-thread buffer to avoid allocating a LinkedBuffer per object. */
    private static final ThreadLocal<LinkedBuffer> BUFFER =
            ThreadLocal.withInitial(() -> LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE));

    @Override
    public ObjectOutput serialize(OutputStream output) throws IOException {
        return new ProtostuffObjectOutput(output);
    }

    @Override
    public ObjectInput deserialize(InputStream input) throws IOException {
        return new ProtostuffObjectInput(input);
    }

    @Override
    public byte getSerializationNumber() {
        return 2;
    }

    // ---- streaming output (DataOutputStream for primitives, tagged payload for objects) ----

    private static class ProtostuffObjectOutput implements ObjectOutput {
        private final DataOutputStream dos;

        ProtostuffObjectOutput(OutputStream os) {
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
            writeAny(dos, obj);
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

    // ---- streaming input (DataInputStream for primitives, tagged payload for objects) ----

    private static class ProtostuffObjectInput implements ObjectInput {
        private final DataInputStream dis;

        ProtostuffObjectInput(InputStream is) {
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
        public Object readObject() throws IOException, ClassNotFoundException {
            return readAny(dis);
        }

        @Override
        public <T> T readObject(Class<T> clazz) throws IOException, ClassNotFoundException {
            return clazz.cast(readObject());
        }

        @Override
        public void close() throws IOException {
            dis.close();
        }
    }

    // ---- tagged object encoding ----

    static void writeAny(DataOutputStream dos, Object obj) throws IOException {
        if (obj == null) {
            dos.writeInt(TAG_NULL);
            return;
        }
        // Top-level collections are handled recursively because protostuff-runtime
        // cannot reliably serialize bare collections as root messages.
        if (obj instanceof List<?> list) {
            dos.writeInt(TAG_LIST);
            dos.writeInt(list.size());
            for (Object e : list) {
                writeAny(dos, e);
            }
            return;
        }
        if (obj instanceof Map<?, ?> map) {
            dos.writeInt(TAG_MAP);
            dos.writeInt(map.size());
            for (Map.Entry<?, ?> e : map.entrySet()) {
                writeAny(dos, e.getKey());
                writeAny(dos, e.getValue());
            }
            return;
        }
        byte[] bytes = toProtostuffBytes(obj);
        dos.writeInt(bytes.length);
        dos.writeUTF(obj.getClass().getName());
        dos.write(bytes);
    }

    static Object readAny(DataInputStream dis) throws IOException {
        try {
            return doReadAny(dis);
        } catch (ClassNotFoundException e) {
            throw new IOException("Failed to resolve embedded class name", e);
        }
    }

    private static Object doReadAny(DataInputStream dis) throws IOException, ClassNotFoundException {
        int tag = dis.readInt();
        return switch (tag) {
            case TAG_NULL -> null;
            case TAG_LIST -> {
                int size = dis.readInt();
                List<Object> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    list.add(doReadAny(dis));
                }
                yield list;
            }
            case TAG_MAP -> {
                int size = dis.readInt();
                Map<Object, Object> map = new HashMap<>(size);
                for (int i = 0; i < size; i++) {
                    map.put(doReadAny(dis), doReadAny(dis));
                }
                yield map;
            }
            default -> {
                String className = dis.readUTF();
                byte[] bytes = new byte[tag];
                dis.readFully(bytes);
                yield fromProtostuffBytes(bytes, Class.forName(className));
            }
        };
    }

    // ---- protostuff helpers ----

    private static <T> byte[] toProtostuffBytes(T obj) {
        @SuppressWarnings("unchecked")
        Schema<T> schema = RuntimeSchema.getSchema((Class<T>) obj.getClass());
        LinkedBuffer buffer = BUFFER.get();
        try {
            return ProtostuffIOUtil.toByteArray(obj, schema, buffer);
        } finally {
            buffer.clear();
        }
    }

    private static <T> T fromProtostuffBytes(byte[] bytes, Class<T> clazz) {
        Schema<T> schema = RuntimeSchema.getSchema(clazz);
        T message = schema.newMessage();
        ProtostuffIOUtil.mergeFrom(bytes, message, schema);
        return message;
    }
}
