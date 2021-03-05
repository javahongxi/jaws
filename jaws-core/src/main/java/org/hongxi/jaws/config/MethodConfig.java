package org.hongxi.jaws.config;

import org.hongxi.jaws.config.annotation.ConfigDesc;

/**
 * Created by shenhongxi on 2021/3/5.
 */
public class MethodConfig extends AbstractConfig {

    private static final long serialVersionUID = -1996115906176873773L;

    // 方法名
    private String name;
    // 超时时间
    private Integer requestTimeout;
    // 失败重试次数（默认为0，不重试）
    private Integer retries;
    // 最大并发调用
    // TODO 暂未实现
    private Integer actives;
    // 参数类型（逗号分隔）
    private String argumentTypes;
    private Integer slowThreshold;

    @ConfigDesc(excluded = true)
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public Integer getActives() {
        return actives;
    }

    public void setActives(Integer actives) {
        this.actives = actives;
    }

    @ConfigDesc(excluded = true)
    public String getArgumentTypes() {
        return argumentTypes;
    }

    public void setArgumentTypes(String argumentTypes) {
        this.argumentTypes = argumentTypes;
    }

    public Integer getSlowThreshold() {
        return slowThreshold;
    }

    public void setSlowThreshold(Integer slowThreshold) {
        this.slowThreshold = slowThreshold;
    }
}