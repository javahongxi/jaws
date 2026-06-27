package org.hongxi.jaws.serialize;

import com.alibaba.com.caucho.hessian.io.Hessian2Input;
import com.alibaba.com.caucho.hessian.io.Hessian2Output;
import org.hongxi.jaws.codec.Serialization;
import org.hongxi.jaws.common.extension.SpiMeta;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * hessian2 序列化，要求序列化的对象实现 java.io.Serializable 接口
 * <p>
 * Created by shenhongxi on 2020/7/28.
 *
 */
@SpiMeta(name = "hessian2")
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
        T result = (T) input.readObject(clazz);
        input.reset();
        return result;
    }

    @Override
    public int getSerializationNumber() {
        return 0;
    }
}
