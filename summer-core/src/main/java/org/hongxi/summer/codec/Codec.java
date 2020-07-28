package org.hongxi.summer.codec;

import org.hongxi.summer.transport.Channel;

import java.io.IOException;

/**
 * Created by shenhongxi on 2020/6/25.
 */
public interface Codec {

    byte[] encode(Channel channel, Object message) throws IOException;

    Object decode(Channel channel, String remoteIp, byte[] data) throws IOException;
}
