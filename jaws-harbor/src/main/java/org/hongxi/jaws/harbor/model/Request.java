package org.hongxi.jaws.harbor.model;

/**
 * Base class for all Nacos-compatible request objects.
 * <p>
 * The {@code namespace} field is shared by most naming requests.
 * Subclasses add domain-specific fields (serviceName, dataId, etc.).
 *
 * @author shenhongxi
 */
public class Request {

    private String namespace;

    public Request() {
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }
}
