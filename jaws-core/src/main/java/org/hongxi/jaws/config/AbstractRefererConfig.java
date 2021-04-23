package org.hongxi.jaws.config;

/**
 * Created by shenhongxi on 2021/4/23.
 */
public abstract class AbstractRefererConfig extends AbstractInterfaceConfig {

    private static final long serialVersionUID = -8953815191278008453L;

    // 服务接口的mock类SLA
    protected String mean;
    protected String p90;
    protected String p99;
    protected String p999;
    protected String errorRate;
    protected Boolean asyncInitConnection;

    public String getMean() {
        return mean;
    }

    public void setMean(String mean) {
        this.mean = mean;
    }

    public String getP90() {
        return p90;
    }

    public void setP90(String p90) {
        this.p90 = p90;
    }

    public String getP99() {
        return p99;
    }

    public void setP99(String p99) {
        this.p99 = p99;
    }

    public String getP999() {
        return p999;
    }

    public void setP999(String p999) {
        this.p999 = p999;
    }

    public String getErrorRate() {
        return errorRate;
    }

    public void setErrorRate(String errorRate) {
        this.errorRate = errorRate;
    }

    public Boolean getAsyncInitConnection() {
        return asyncInitConnection;
    }

    public void setAsyncInitConnection(Boolean asyncInitConnection) {
        this.asyncInitConnection = asyncInitConnection;
    }

}