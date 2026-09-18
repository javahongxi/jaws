package org.hongxi.jaws.harbor.model.response;

import org.hongxi.jaws.harbor.model.Response;

/**
 * Client acknowledgement of a pushed notification. Nacos defines it empty and
 * its server only logs it; harbor keeps the class so the token-equals-class-name
 * rule still holds when that frame arrives on the bi-stream.
 *
 * @author shenhongxi
 */
public class NotifySubscriberResponse extends Response {

    public NotifySubscriberResponse() {
    }
}
