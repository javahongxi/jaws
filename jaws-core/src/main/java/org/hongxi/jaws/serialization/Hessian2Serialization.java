package org.hongxi.jaws.serialization;

import com.alibaba.com.caucho.hessian.io.Hessian2Input;
import com.alibaba.com.caucho.hessian.io.Hessian2Output;
import org.hongxi.jaws.common.extension.SpiMeta;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Hessian2-based serialization implementation.
 * Objects to be serialized must implement the {@link java.io.Serializable} interface.
 * <p>
 * Created by shenhongxi on 2020/7/28.
 */
@SpiMeta(name = "hessian2", number = 0)
public class Hessian2Serialization implements Serialization {

    @Override
    public byte[] serialize(Object data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Hessian2Output out = new Hessian2Output(bos);
        out.writeObject(data);
        out.flushBuffer();
        out.reset();
        return bos.toByteArray();
    }

    @Override
    public <T> T deserialize(byte[] data, Class<T> clazz) throws IOException {
        Hessian2Input input = new Hessian2Input(new ByteArrayInputStream(data));
        // noinspection unchecked
        T result = (T) input.readObject(clazz);
        input.reset();
        return result;
    }

    @Override
    public byte getSerializationNumber() {
        return 0;
    }
}
