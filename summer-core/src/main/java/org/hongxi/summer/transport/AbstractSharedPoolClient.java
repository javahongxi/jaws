package org.hongxi.summer.transport;

import org.hongxi.summer.common.URLParamType;
import org.hongxi.summer.core.DefaultThreadFactory;
import org.hongxi.summer.core.StandardThreadPoolExecutor;
import org.hongxi.summer.rpc.URL;

import java.util.ArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by shenhongxi on 2020/7/28.
 */
public abstract class AbstractSharedPoolClient extends AbstractClient {

    private static final ThreadPoolExecutor EXECUTOR = new StandardThreadPoolExecutor(1, 300, 20000,
            new DefaultThreadFactory("AbstractPoolClient-initPool-", true));
    private final AtomicInteger idx = new AtomicInteger();
    protected SharedObjectFactory factory;
    protected ArrayList<Channel> channels;
    protected int connections;

    public AbstractSharedPoolClient(URL url) {
        super(url);
        connections = url.getIntParameter(URLParamType.minClientConnections.getName(),
                URLParamType.minClientConnections.intValue());
        if (connections <= 0) {
            connections = URLParamType.minClientConnections.intValue();
        }
    }
}
