package org.hongxi.jaws.rpc;

import java.util.Map;

/**
 * Represents an RPC request sent from consumer to provider.
 * <p>
 * Carries the target service interface, method name, parameter descriptor,
 * arguments, and framework-level attachments propagated through the invocation chain.
 *
 * @author shenhongxi
 */
public interface Request {

    /**
     * Returns the fully-qualified name of the target service interface.
     *
     * @return service interface name
     */
    String getInterfaceName();

    /**
     * Returns the name of the target method to invoke.
     *
     * @return method name
     */
    String getMethodName();

    /**
     * Returns the parameter descriptor (type signature) used for method overload resolution.
     * <p>
     * For example, a method {@code void hello(String, int)} yields {@code "java.lang.String,int"}.
     *
     * @return parameter type descriptor
     */
    String getParamDesc();

    /**
     * Returns the actual arguments to pass to the target method.
     *
     * @return argument array, or {@code null} if the method has no parameters
     */
    Object[] getArguments();

    /**
     * Returns all framework-level attachments (key-value pairs) carried by this request.
     * <p>
     * Attachments are transparently propagated from consumer to provider
     * and are not part of the business method signature.
     *
     * @return immutable or mutable map of attachments
     */
    Map<String, String> getAttachments();

    /**
     * Sets a framework-level attachment on this request.
     *
     * @param name  attachment key
     * @param value attachment value
     */
    void setAttachment(String name, String value);

    /**
     * Returns the unique request id, used to correlate request and response.
     *
     * @return request id
     */
    long getRequestId();

    /**
     * Returns the number of retries remaining for this request.
     *
     * @return retries left; 0 means no more retries
     */
    int getRetries();

    /**
     * Returns the serialization protocol identifier used for encoding this request.
     *
     * @return serialization number
     */
    byte getSerializationNumber();
}