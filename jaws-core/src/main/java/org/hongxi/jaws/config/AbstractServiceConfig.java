package org.hongxi.jaws.config;

/**
 * Created by shenhongxi on 2021/3/6.
 */
public abstract class AbstractServiceConfig extends AbstractInterfaceConfig {

    private static final long serialVersionUID = 6798383581370989643L;
    /**
     * 一个service可以按多个protocol提供服务，不同protocol使用不同port 利用export来设置protocol和port，格式如下：
     * protocol1:port1,protocol2:port2
     **/
    protected String export;

    /** 一般不用设置，由服务自己获取，但如果有多个ip，而只想用指定ip，则可以在此处指定 */
    protected String host;

    public String getExport() {
        return export;
    }

    public void setExport(String export) {
        this.export = export;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }
}
