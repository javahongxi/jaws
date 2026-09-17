package org.hongxi.jaws.wire;

import com.google.protobuf.Message;

/**
 * A {@link WireClientCall} that forwards all methods to a delegate.
 * Subclass and override specific methods to intercept outbound requests
 * without short-circuiting the call.
 * <p>
 * Example — inject an auth token:
 * <pre>{@code
 * new ForwardingClientCall(delegate) {
 *     @Override
 *     public void sendMessage(Message request) {
 *         putAttachment("authorization", tokenProvider.getToken());
 *         super.sendMessage(request);
 *     }
 * }
 * }</pre>
 *
 * @author shenhongxi
 * @see WireClientInterceptor
 */
public class ForwardingClientCall implements WireClientCall {

    private final WireClientCall delegate;

    public ForwardingClientCall(WireClientCall delegate) {
        this.delegate = delegate;
    }

    /**
     * @return the delegate call this forwarding instance wraps
     */
    protected WireClientCall delegate() {
        return delegate;
    }

    @Override
    public WireCallContext context() {
        return delegate.context();
    }

    @Override
    public String path() {
        return delegate.path();
    }

    @Override
    public void putAttachment(String key, String value) {
        delegate.putAttachment(key, value);
    }

    @Override
    public void sendMessage(Message request) {
        delegate.sendMessage(request);
    }

    @Override
    public void cancel(String reason) {
        delegate.cancel(reason);
    }
}
