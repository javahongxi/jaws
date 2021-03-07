package org.hongxi.jaws.filter;

import org.hongxi.jaws.common.extension.Spi;
import org.hongxi.jaws.rpc.Caller;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;

/**
 * 
 * filter before transport.
 *
 * Created by shenhongxi on 2021/3/6.
 */
@Spi
public interface Filter {

    Response filter(Caller<?> caller, Request request);
}