package org.hongxi.jaws.store;

import java.io.Serializable;

/**
 * Broker identity reported over RPC. Consumers key their offsets by
 * brokerName, so they fetch it once per discovered address instead of
 * deriving it from host:port (which drifts across restarts).
 */
public class BrokerInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private String brokerName;

    public BrokerInfo() {
    }

    public BrokerInfo(String brokerName) {
        this.brokerName = brokerName;
    }

    public String getBrokerName() {
        return brokerName;
    }

    public void setBrokerName(String brokerName) {
        this.brokerName = brokerName;
    }
}