package org.hongxi.jaws.serialization;

import com.alibaba.fastjson2.JSONB;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import org.hongxi.jaws.common.extension.Extension;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Fastjson2-based serialization implementation.
 * <p>
 * Since JSONB does not natively support streaming serialization, the ObjectOutput /
 * ObjectInput use a hybrid approach: primitive types (UTF, int, long) are written
 * via {@link DataOutputStream} / {@link DataInputStream}, while objects are serialized
 * to JSONB byte[] with a 4-byte length prefix.
 * <p>
 * This preserves the streaming interface contract while accommodating JSONB's
 * byte-array-oriented design.
 *
 * @author shenhongxi
 * @since 2020/7/28
 */
@Extension(value = "fastjson2", number = 1)
public class FastJson2Serialization implements Serialization {

    private static final JSONWriter.Feature[] WRITE_FEATURES = {
            JSONWriter.Feature.WriteClassName,
            JSONWriter.Feature.FieldBased,
            JSONWriter.Feature.ErrorOnNoneSerializable,
            JSONWriter.Feature.ReferenceDetection,
            JSONWriter.Feature.WriteNulls,
            JSONWriter.Feature.NotWriteDefaultValue,
            JSONWriter.Feature.NotWriteHashMapArrayListClassName,
            JSONWriter.Feature.WriteNameAsSymbol
    };

    private static final JSONReader.Feature[] READ_FEATURES = {
            JSONReader.Feature.UseDefaultConstructorAsPossible,
            JSONReader.Feature.ErrorOnNoneSerializable,
            JSONReader.Feature.IgnoreAutoTypeNotMatch,
            JSONReader.Feature.UseNativeObject,
            JSONReader.Feature.FieldBased
    };

    private final Fastjson2SecurityFilter securityFilter = new Fastjson2SecurityFilter();

    @Override
    public ObjectOutput serialize(OutputStream output) throws IOException {
        return new Fastjson2ObjectOutput(output);
    }

    @Override
    public ObjectInput deserialize(InputStream input) throws IOException {
        return new Fastjson2ObjectInput(input, securityFilter);
    }

    /**
     * Returns the security filter, which can be used to configure allow/deny lists
     * or switch the check mode.
     *
     * @return the security filter instance
     */
    public Fastjson2SecurityFilter getSecurityFilter() {
        return securityFilter;
    }

    @Override
    public byte getSerializationNumber() {
        return 1;
    }

    // ---- streaming output (DataOutputStream for primitives, length-prefixed JSONB for objects) ----

    private static class Fastjson2ObjectOutput implements ObjectOutput {
        private final DataOutputStream dos;

        Fastjson2ObjectOutput(OutputStream os) {
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
            byte[] bytes = JSONB.toBytes(obj, WRITE_FEATURES);
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

    // ---- streaming input (DataInputStream for primitives, length-prefixed JSONB for objects) ----

    private static class Fastjson2ObjectInput implements ObjectInput {
        private final DataInputStream dis;
        private final Fastjson2SecurityFilter securityFilter;

        Fastjson2ObjectInput(InputStream is, Fastjson2SecurityFilter securityFilter) {
            this.dis = new DataInputStream(is);
            this.securityFilter = securityFilter;
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
            return JSONB.parseObject(bytes, Object.class, securityFilter, READ_FEATURES);
        }

        @Override
        public <T> T readObject(Class<T> clazz) throws IOException {
            int len = dis.readInt();
            if (len < 0) {
                return null;
            }
            byte[] bytes = new byte[len];
            dis.readFully(bytes);
            return JSONB.parseObject(bytes, clazz, securityFilter, READ_FEATURES);
        }

        @Override
        public void close() throws IOException {
            dis.close();
        }
    }
}
