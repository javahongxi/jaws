package org.hongxi.jaws.mock;

import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.Client;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.transport.Server;
import org.hongxi.jaws.transport.support.AbstractEndpointFactory;

/**
 * Created by shenhongxi on 2021/4/21.
 */
@SpiMeta(name = "mockEndpoint")
public class MockEndpointFactory extends AbstractEndpointFactory {

    @Override
    protected Server innerCreateServer(URL url, MessageHandler messageHandler) {
        return new MockServer(url);
    }

    @Override
    protected Client innerCreateClient(URL url) {
        return new MockClient(url);
    }

}
