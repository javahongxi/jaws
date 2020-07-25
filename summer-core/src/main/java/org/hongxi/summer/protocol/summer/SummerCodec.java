package org.hongxi.summer.protocol.summer;

import org.hongxi.summer.codec.AbstractCodec;
import org.hongxi.summer.core.extension.SpiMeta;
import org.hongxi.summer.transport.Channel;

import java.io.IOException;

/**
 * Created by shenhongxi on 2020/7/25.
 */
@SpiMeta(name = "summer")
public class SummerCodec extends AbstractCodec {
    public static final short MAGIC = (short) 0xF0F0;

    private static final byte MASK = 0x07;

    @Override
    public byte[] encode(Channel channel, Object message) throws IOException {
        return new byte[0];
    }

    @Override
    public Object decode(Channel channel, String remoteIp, byte[] buffer) throws IOException {
        return null;
    }
}
