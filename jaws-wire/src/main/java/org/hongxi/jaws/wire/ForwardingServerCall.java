package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import org.hongxi.jaws.stream.StreamSource;

/**
 * A {@link WireServerCall} that forwards all methods to a delegate.
 * Subclass and override specific methods to intercept outbound responses
 * without short-circuiting the call.
 * <p>
 * Example — observe the response status:
 * <pre>{@code
 * new ForwardingServerCall(delegate) {
 *     @Override
 *     public void close(int status, String message) {
 *         log.info("call {} closed with status {}", path(), status);
 *         super.close(status, message);
 *     }
 * }
 * }</pre>
 *
 * @author shenhongxi
 * @see WireServerInterceptor
 */
public class ForwardingServerCall implements WireServerCall {

    private final WireServerCall delegate;

    public ForwardingServerCall(WireServerCall delegate) {
        this.delegate = delegate;
    }

    /**
     * @return the delegate call this forwarding instance wraps
     */
    protected WireServerCall delegate() {
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
    public void sendMessage(Message response) {
        delegate.sendMessage(response);
    }

    @Override
    public void dispatchStream(StreamSource<Message> source) {
        delegate.dispatchStream(source);
    }

    @Override
    public void close(int status, String message) {
        delegate.close(status, message);
    }

    @Override
    public boolean isCancelled() {
        return delegate.isCancelled();
    }
}
