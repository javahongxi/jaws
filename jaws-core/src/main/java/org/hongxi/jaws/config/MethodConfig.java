package org.hongxi.jaws.config;

import org.hongxi.jaws.config.annotation.ConfigDesc;
import java.io.Serial;

/**
 * Created by shenhongxi on 2021/3/5.
 */
public class MethodConfig extends AbstractConfig {

    @Serial
    private static final long serialVersionUID = -1996115906176873773L;

    /**
     * The method name
     */
    private String name;

    /**
     * The argument types of method, split by comma
     */
    private String argumentTypes;

    /**
     * The request timeout
     */
    private Integer requestTimeout;

    /**
     * The retries times when request failed
     */
    private Integer retries;

    @ConfigDesc(excluded = true)
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @ConfigDesc(excluded = true)
    public String getArgumentTypes() {
        return argumentTypes;
    }

    public void setArgumentTypes(String argumentTypes) {
        this.argumentTypes = argumentTypes;
    }

    public Integer getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Integer requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Integer getRetries() {
        return retries;
    }

    public void setRetries(Integer retries) {
        this.retries = retries;
    }
}