package org.hongxi.jaws.harbor.model.response;

/**
 * Reply to a batch instance request. Same shape as {@link InstanceResponse};
 * it exists as its own class because Nacos names the reply after the request
 * it answers, and the token on the wire must be this class's simple name.
 *
 * @author shenhongxi
 */
public class BatchInstanceResponse extends InstanceResponse {

    public BatchInstanceResponse() {
        super();
    }

    public BatchInstanceResponse(String type) {
        super();
        setType(type);
    }
}
