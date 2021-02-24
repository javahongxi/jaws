package org.hongxi.jaws.transport.netty;

import org.hongxi.jaws.common.util.RequestIdGenerator;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.*;
import org.hongxi.jaws.transport.Channel;
import org.hongxi.jaws.transport.MessageHandler;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Created by shenhongxi on 2020/8/22.
 */
public class NettyClientTest {

    private NettyServer nettyServer;
    private NettyClient nettyClient;
    private DefaultRequest request;
    private URL url;

    @Before
    public void setUp() {
        Map<String, String> parameters = new HashMap<>();
        parameters.put("requestTimeout", "500");

        String interfaceName = "org.hongxi.jaws.protocol.example.HelloService";
        url = new URL("netty", "localhost", 18080, interfaceName, parameters);

        request = new DefaultRequest();
        request.setRequestId(RequestIdGenerator.getRequestId());
        request.setInterfaceName(interfaceName);
        request.setMethodName("hello");
        request.setParametersDesc("void");

        nettyServer = new NettyServer(url, new MessageHandler() {
            @Override
            public Object handle(Channel channel, Object message) {
                Request request = (Request) message;
                DefaultResponse response = new DefaultResponse();
                response.setRequestId(request.getRequestId());
                response.setValue("method: " + request.getMethodName() + " requestId: " + request.getRequestId());

                return response;
            }
        });

        nettyServer.open();
    }

    @After
    public void tearDown() {
        if (nettyClient != null) {
            nettyClient.close();
        }
        nettyServer.close();
    }

    @Test
    public void testNormal() throws InterruptedException {
        nettyClient = new NettyClient(url);
        nettyClient.open();

        Response response;
        try {
            response = nettyClient.request(request);
            Object result = response.getValue();

            Assert.assertNotNull(result);
            Assert.assertEquals("method: " + request.getMethodName() + " requestId: " + request.getRequestId(), result);
        } catch (JawsServiceException e) {
            fail(e.getMessage());
        } catch (Exception e) {
            fail();
        }
    }

    @Test
    public void testClient() throws InterruptedException {
        nettyServer.close();

        NettyTestClient nettyClient = new NettyTestClient(url);
        this.nettyClient = nettyClient;
        nettyClient.open();

        for (Channel channel : nettyClient.getChannels()) {
            assertFalse(channel.isAvailable());
        }

        nettyServer.open();

        try {
            nettyClient.request(request);
        } catch (Exception e) {
            fail("request error");
        }

        Thread.sleep(3000);
        for (Channel channel : nettyClient.getChannels()) {
            assertTrue(channel.isAvailable());
        }
    }

    static class NettyTestClient extends NettyClient {

        public NettyTestClient(URL url) {
            super(url);
        }

        public List<Channel> getChannels() {
            return super.channels;
        }

        public Channel getChannel0() {
            return super.getChannel();
        }
    }
}
