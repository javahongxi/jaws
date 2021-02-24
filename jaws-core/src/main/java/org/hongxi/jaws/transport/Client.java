package org.hongxi.jaws.transport;

import org.hongxi.jaws.rpc.Request;

/**
 * Created by shenhongxi on 2020/7/28.
 */
public interface Client extends Endpoint {

    void heartbeat(Request request);
}
