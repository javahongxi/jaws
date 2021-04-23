package org.hongxi.jaws.registry.zookeeper;

import org.I0Itec.zkclient.exception.ZkMarshallingError;
import org.I0Itec.zkclient.serialize.SerializableSerializer;
import org.hongxi.jaws.common.util.ByteUtils;

import java.io.ObjectStreamConstants;
import java.nio.charset.StandardCharsets;

/**
 * Created by shenhongxi on 2021/4/24.
 */
public class StringSerializer extends SerializableSerializer {
    @Override
    public Object deserialize(byte[] bytes) throws ZkMarshallingError {
        if (bytes == null){
            return null;
        }
        if (bytes.length > 1 && ByteUtils.bytes2short(bytes, 0) == ObjectStreamConstants.STREAM_MAGIC) {
            return super.deserialize(bytes);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public byte[] serialize(Object obj) throws ZkMarshallingError {
        return obj.toString().getBytes(StandardCharsets.UTF_8);
    }
}