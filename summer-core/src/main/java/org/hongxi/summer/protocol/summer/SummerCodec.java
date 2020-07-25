package org.hongxi.summer.protocol.summer;

import org.hongxi.summer.codec.AbstractCodec;
import org.hongxi.summer.codec.Serialization;
import org.hongxi.summer.core.extension.SpiMeta;
import org.hongxi.summer.rpc.Request;
import org.hongxi.summer.transport.Channel;

import java.io.IOException;

/**
 * Created by shenhongxi on 2020/7/25.
 */
@SpiMeta(name = "summer")
public class SummerCodec extends AbstractCodec {
    private static final byte MASK = 0x07;
    private static final int HEADER_SIZE = 13;

    static {
        initAllSerialization();
    }

    @Override
    public byte[] encode(Channel channel, Object message) throws IOException {
        SummerHeader header = new SummerHeader();
        byte[] body = null;
        Serialization serialization;
        GrowableByteBuffer buf = new GrowableByteBuffer(4096);
        // meta
        int index = HEADER_SIZE;
        buf.position(index);
        buf.putInt(0); // metasize

        if (message instanceof Request) {

        }
        return new byte[0];
    }

    @Override
    public Object decode(Channel channel, String remoteIp, byte[] buffer) throws IOException {
        return null;
    }
}
