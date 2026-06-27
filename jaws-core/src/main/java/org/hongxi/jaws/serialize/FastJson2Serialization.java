package org.hongxi.jaws.serialize;

import com.alibaba.fastjson2.JSONB;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import org.hongxi.jaws.codec.Serialization;
import org.hongxi.jaws.common.extension.SpiMeta;

import java.io.IOException;

/**
 * fastjson2 序列化
 * <p>
 * Created by shenhongxi on 2020/7/28.
 */
@SpiMeta(name = "fastjson2")
public class FastJson2Serialization implements Serialization {

    @Override
    public byte[] serialize(Object data) throws IOException {
        return JSONB.toBytes(
                data,
                JSONWriter.Feature.WriteClassName,
                JSONWriter.Feature.FieldBased,
                JSONWriter.Feature.ErrorOnNoneSerializable,
                JSONWriter.Feature.ReferenceDetection,
                JSONWriter.Feature.WriteNulls,
                JSONWriter.Feature.NotWriteDefaultValue,
                JSONWriter.Feature.NotWriteHashMapArrayListClassName,
                JSONWriter.Feature.WriteNameAsSymbol);
    }

    @Override
    public <T> T deserialize(byte[] data, Class<T> clazz) throws IOException {
        return JSONB.parseObject(
                data,
                clazz,
                JSONReader.Feature.UseDefaultConstructorAsPossible,
                JSONReader.Feature.ErrorOnNoneSerializable,
                JSONReader.Feature.IgnoreAutoTypeNotMatch,
                JSONReader.Feature.UseNativeObject,
                JSONReader.Feature.FieldBased,
                JSONReader.Feature.SupportSmartMatch,
                JSONReader.Feature.SupportAutoType);
    }

    @Override
    public int getSerializationNumber() {
        return 1;
    }
}
