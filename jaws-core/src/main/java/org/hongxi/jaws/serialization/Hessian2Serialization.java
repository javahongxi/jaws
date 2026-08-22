package org.hongxi.jaws.serialization;

import com.alibaba.com.caucho.hessian.io.Hessian2Input;
import com.alibaba.com.caucho.hessian.io.Hessian2Output;
import org.hongxi.jaws.common.extension.Extension;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Hessian2-based serialization implementation.
 * <p>
 * Hessian2 natively supports streaming serialization, so the ObjectOutput /
 * ObjectInput wrappers delegate directly to Hessian2Output / Hessian2Input
 * with zero intermediate byte[] allocation.
 * <p>
 * Objects to be serialized must implement the {@link java.io.Serializable} interface.
 *
 * @author shenhongxi
 * @since 2020/7/28
 */
@Extension(name = "hessian2", number = 0)
public class Hessian2Serialization implements Serialization {

    @Override
    public ObjectOutput serialize(OutputStream output) throws IOException {
        return new Hessian2ObjectOutput(output);
    }

    @Override
    public ObjectInput deserialize(InputStream input) throws IOException {
        return new Hessian2ObjectInput(input);
    }

    @Override
    public byte getSerializationNumber() {
        return 0;
    }

    // ---- streaming output wrapper ----

    private static class Hessian2ObjectOutput implements ObjectOutput {
        private final Hessian2Output out;

        Hessian2ObjectOutput(OutputStream os) {
            this.out = new Hessian2Output(os);
        }

        @Override
        public void writeUTF(String value) throws IOException {
            out.writeString(value);
        }

        @Override
        public void writeInt(int value) throws IOException {
            out.writeInt(value);
        }

        @Override
        public void writeLong(long value) throws IOException {
            out.writeLong(value);
        }

        @Override
        public void writeObject(Object obj) throws IOException {
            out.writeObject(obj);
        }

        @Override
        public void flush() throws IOException {
            out.flushBuffer();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    // ---- streaming input wrapper ----

    private static class Hessian2ObjectInput implements ObjectInput {
        private final Hessian2Input in;

        Hessian2ObjectInput(InputStream is) {
            this.in = new Hessian2Input(is);
        }

        @Override
        public String readUTF() throws IOException {
            return in.readString();
        }

        @Override
        public int readInt() throws IOException {
            return in.readInt();
        }

        @Override
        public long readLong() throws IOException {
            return in.readLong();
        }

        @Override
        public Object readObject() throws IOException {
            return in.readObject();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T readObject(Class<T> clazz) throws IOException {
            return (T) in.readObject(clazz);
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
