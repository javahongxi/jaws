package org.hongxi.jaws.codec;

import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;
import org.hongxi.jaws.transport.Channel;

import java.io.IOException;

/**
 * Created by shenhongxi on 2020/6/25.
 */
@Spi(scope = Scope.PROTOTYPE)
public interface Codec {

    byte[] encode(Channel channel, Object message) throws IOException;

    Object decode(Channel channel, String remoteIp, byte[] data) throws IOException;
}
