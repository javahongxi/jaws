package org.hongxi.jaws.transport;

import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;
import org.hongxi.jaws.rpc.URL;

/**
 * Created by shenhongxi on 2020/7/31.
 */
@Spi(scope = Scope.SINGLETON)
public interface EndpointFactory {

    /**
     * create remote server
     *
     * @param url
     * @param messageHandler
     * @return
     */
    Server createServer(URL url, MessageHandler messageHandler);

    /**
     * create remote client
     *
     * @param url
     * @return
     */
    Client createClient(URL url);
}
