package org.hongxi.summer.codec;

import org.hongxi.summer.transport.Channel;

import java.io.IOException;
import java.util.concurrent.ConcurrentMap;

/**
 * Created by shenhongxi on 2020/7/25.
 */
public abstract class AbstractCodec implements Codec {
    protected static ConcurrentMap<Integer, String> serializations;


}
