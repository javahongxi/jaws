package org.hongxi.jaws.serialization;

import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;

import java.io.IOException;

/**
 * Created by shenhongxi on 2020/7/25.
 */
@Spi(scope = Scope.SINGLETON)
public interface Serialization {

    byte[] serialize(Object obj) throws IOException;

    <T> T deserialize(byte[] bytes, Class<T> clazz) throws IOException;

    /**
     * Returns the unique identifier for this serialization, used in the protocol header
     * to specify the serialization method per message.
     * <p>
     * The identifier is stored in the high 5 bits of the flag byte in the Jaws protocol header,
     * so the value must be in the range 0-31 (max 32 serialization types).
     *
     * @return serialization number (0-31)
     */
    byte getSerializationNumber();
}
