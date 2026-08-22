package org.hongxi.jaws.config;

import java.io.Serial;
import java.util.Map;

/**
 * Method-level configuration that overrides interface-level settings for a
 * single service method, such as request timeout and retries on failure.
 * <p>
 * Attach {@code MethodConfig} instances to {@link ServiceConfig} or
 * {@link ReferenceConfig} to apply per-method parameters to the exported
 * or subscribed URL.
 *
 * @see ServiceConfig
 * @see ReferenceConfig
 *
 * <p>
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

    @Override
    protected void collectParams(Map<String, String> params) {
        putIfPresent(params, "requestTimeout", requestTimeout);
        putIfPresent(params, "retries", retries);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

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