package org.hongxi.jaws.rpc;

/**
 * Combines {@link Future} and {@link Response}: a handle created per client-side call
 * that is completed by the network layer when the server reply arrives.
 * Callers may block via {@link Future#getValue()} or register a {@link FutureListener}.
 *
 * <p>Created by shenhongxi on 2020/7/30.
 */
public interface ResponseFuture extends Response, Future {

    void onSuccess(Response response);

    void onFailure(Response response);
}
