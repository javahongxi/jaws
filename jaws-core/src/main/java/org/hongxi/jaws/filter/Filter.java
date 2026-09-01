package org.hongxi.jaws.filter;

import org.hongxi.jaws.common.extension.Spi;
import org.hongxi.jaws.rpc.Caller;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;

import java.util.concurrent.CompletableFuture;

/**
 * Filter applied before transport on both consumer and provider sides.
 * <p>
 * The async contract ensures filters compose naturally with the rest of the
 * framework's non-blocking chain: implementations should call
 * {@link Caller#callAsync(Request)} and chain pre/post logic via
 * {@code thenApply} / {@code whenComplete}.
 *
 * <p>Created by shenhongxi on 2021/3/6.
 */
@Spi
public interface Filter {

    CompletableFuture<Response> filter(Caller<?> caller, Request request);
}