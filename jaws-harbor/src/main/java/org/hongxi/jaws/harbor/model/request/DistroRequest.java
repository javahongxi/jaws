package org.hongxi.jaws.harbor.model.request;

import org.hongxi.jaws.harbor.model.Request;

/**
 * Base class for Distro inter-node protocol requests.
 *
 * @author shenhongxi
 */
public class DistroRequest extends Request {

    private String resourceType;

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }
}
