package org.hongxi.jaws.rpc;

import org.hongxi.jaws.exception.JawsServiceException;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Default serializable {@link Response} carried over the wire between provider and
 * consumer. Holds either the return value or an exception (never both), plus the
 * request id, process time, attachments, and a serialization number identifying the
 * codec used. Note that {@link #getValue()} throws the embedded exception if one is set;
 * use {@link #getRawValue()} to read the value unconditionally.
 *
 * <p>Created by shenhongxi on 2020/7/25.
 */
public class DefaultResponse implements Response, Serializable {
    @Serial
    private static final long serialVersionUID = -46598719225168485L;

    private Object value;
    private Exception exception;
    private long requestId;
    private long processTime;
    private int timeout;
    // Framework-level attachments carried by the response; reserved for protocol extension
    private Map<String, String> attachments;
    // default serialization is hessian2
    private byte serializationNumber = 0;

    public DefaultResponse() {
    }

    public DefaultResponse(long requestId) {
        this.requestId = requestId;
    }

    public DefaultResponse(Response response) {
        this.value = response.getValue();
        this.exception = response.getException();
        this.requestId = response.getRequestId();
        this.processTime = response.getProcessTime();
        this.timeout = response.getTimeout();
        this.attachments = response.getAttachments();
        this.serializationNumber = response.getSerializationNumber();
    }

    public DefaultResponse(Object value) {
        this.value = value;
    }

    @Override
    public Object getValue() {
        if (exception != null) {
            throw (exception instanceof RuntimeException) ?
                    (RuntimeException) exception :
                    new JawsServiceException(exception.getMessage(), exception);
        }

        return value;
    }

    @Override
    public Object getRawValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    @Override
    public Exception getException() {
        return exception;
    }

    public void setException(Exception exception) {
        this.exception = exception;
    }

    @Override
    public long getRequestId() {
        return requestId;
    }

    public void setRequestId(long requestId) {
        this.requestId = requestId;
    }

    @Override
    public long getProcessTime() {
        return processTime;
    }

    public void setProcessTime(long time) {
        this.processTime = time;
    }

    @Override
    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
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
        if (attachments == null) {
            attachments = new HashMap<>();
        }
        attachments.put(key, value);
    }

    @Override
    public byte getSerializationNumber() {
        return serializationNumber;
    }

    public void setSerializationNumber(byte number) {
        this.serializationNumber = number;
    }
}
