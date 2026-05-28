package org.hongxi.jaws.codec;

import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsFrameworkException;

import java.io.*;

/**
 * Created by shenhongxi on 2020/7/25.
 */
public abstract class AbstractCodec implements Codec {

    protected void serialize(ObjectOutput output, Object message, Serialization serialization) throws IOException {
        if (message == null) {
            output.writeObject(null);
            return;
        }

        output.writeObject(serialization.serialize(message));
    }

    protected Object deserialize(byte[] value, Class<?> type, Serialization serialization) throws IOException {
        if (value == null) {
            return null;
        }

        return serialization.deserialize(value, type);
    }

    public ObjectOutput createOutput(OutputStream out) {
        try {
            return new ObjectOutputStream(out);
        } catch (Exception e) {
            throw new JawsFrameworkException(this.getClass().getSimpleName() + " createOutput error", e,
                    JawsErrorMsgConstants.FRAMEWORK_ENCODE_ERROR);
        }
    }

    public ObjectInput createInput(InputStream in) {
        try {
            return new ObjectInputStream(in);
        } catch (Exception e) {
            throw new JawsFrameworkException(this.getClass().getSimpleName() + " createInput error", e,
                    JawsErrorMsgConstants.FRAMEWORK_DECODE_ERROR);
        }
    }
}
