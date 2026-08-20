package org.hongxi.jaws.serialization;

import com.alibaba.fastjson2.JSONB;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import org.hongxi.jaws.common.extension.SpiMeta;

import java.io.IOException;

/**
 * Fastjson2-based serialization implementation.
 * <p>
 * Created by shenhongxi on 2020/7/28.
 */
@SpiMeta(name = "fastjson2", number = 1)
public class FastJson2Serialization implements Serialization {

    private final Fastjson2SecurityFilter securityFilter = new Fastjson2SecurityFilter();

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
                securityFilter,
                JSONReader.Feature.UseDefaultConstructorAsPossible,
                JSONReader.Feature.ErrorOnNoneSerializable,
                JSONReader.Feature.IgnoreAutoTypeNotMatch,
                JSONReader.Feature.UseNativeObject,
                JSONReader.Feature.FieldBased);
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
}
