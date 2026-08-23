package org.hongxi.jaws.rpc;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default serializable {@link Request} sent from consumer to provider, identifying the
 * target by interface name, method name and parameter descriptor rather than by class
 * references, since both sides may not share the same classloader. Also carries the
 * call arguments, framework attachments, retry count, request id, and the serialization
 * number of the codec used to encode the payload.
 *
 * <p>Created by shenhongxi on 2020/7/28.
 */
public class DefaultRequest implements Request, Serializable {
    @Serial
    private static final long serialVersionUID = -6525078483477733530L;

    /**
     * Generates monotonically increasing, JVM-unique request ids.
     * Ids are only used to correlate a request with its response on the
     * same connection, so a plain sequence is sufficient and wrap-around safe.
     */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private String interfaceName;
    private String methodName;
    private String paramDesc;
    private Object[] arguments;
    private Map<String, String> attachments;
    private int retries = 0;
    private long requestId;
    // default serialization is hessian2
    private byte serializationNumber = 0;

    public DefaultRequest() {
        this.requestId = SEQUENCE.incrementAndGet();
    }

    @Override
    public String getInterfaceName() {
        return interfaceName;
    }

    public void setInterfaceName(String interfaceName) {
        this.interfaceName = interfaceName;
    }

    @Override
    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    @Override
    public String getParamDesc() {
        return paramDesc;
    }

    public void setParamDesc(String paramDesc) {
        this.paramDesc = paramDesc;
    }

    @Override
    public Object[] getArguments() {
        return arguments;
    }

    public void setArguments(Object[] arguments) {
        this.arguments = arguments;
    }

    @Override
    public Map<String, String> getAttachments() {
        return attachments != null ? attachments : Collections.emptyMap();
    }

    public void setAttachments(Map<String, String> attachments) {
        this.attachments = attachments;
    }

    @Override
    public void setAttachment(String key, String value) {
        if (this.attachments == null) {
            this.attachments = new HashMap<>();
        }
        this.attachments.put(key, value);
    }

    @Override
    public long getRequestId() {
        return requestId;
    }

    /**
     * Overwritten with the id carried on the wire when decoded on the server side.
     */
    public void setRequestId(long requestId) {
        this.requestId = requestId;
    }

    @Override
    public int getRetries() {
        return retries;
    }

    @Override
    public void setRetries(int retries) {
        this.retries = retries;
    }

    @Override
    public byte getSerializationNumber() {
        return serializationNumber;
    }

    @Override
    public void setSerializationNumber(byte number) {
        this.serializationNumber = number;
    }

    @Override
    public String toString() {
        return interfaceName + "." + methodName + "(" + paramDesc + ") requestId=" + requestId;
    }
}
