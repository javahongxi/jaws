package org.hongxi.jaws.harbor.model.response;

import org.hongxi.jaws.harbor.model.Response;

/**
 * Response to {@link org.hongxi.jaws.harbor.model.request.InstanceRequest}.
 * The {@code type} field echoes back the operation (registerInstance/deregisterInstance).
 *
 * @author shenhongxi
 */
public class InstanceResponse extends Response {

    private String type;

    public InstanceResponse() {
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
