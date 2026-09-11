package org.hongxi.jaws.harbor.model.response;

import org.hongxi.jaws.harbor.model.Response;

/**
 * Response to {@link org.hongxi.jaws.harbor.model.request.HealthCheckRequest}.
 *
 * @author shenhongxi
 */
public class HealthCheckResponse extends Response {

    private String status;

    public HealthCheckResponse() {
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
