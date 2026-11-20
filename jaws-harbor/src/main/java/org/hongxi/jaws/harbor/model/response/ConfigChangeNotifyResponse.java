package org.hongxi.jaws.harbor.model.response;

import org.hongxi.jaws.harbor.model.Response;

/**
 * The client's ack of a {@code ConfigChangeNotifyRequest} push — the nacos
 * {@code ConfigChangeNotifyResponse} shape. Harbor accepts it on the bi-stream
 * and reads nothing from it: delivery is healed by the listen round, not by
 * the ack, so the frame exists only because nacos-client sends one.
 *
 * @author shenhongxi
 */
public class ConfigChangeNotifyResponse extends Response {

    public ConfigChangeNotifyResponse() {
        setResultCode(200);
        setSuccess(true);
    }
}