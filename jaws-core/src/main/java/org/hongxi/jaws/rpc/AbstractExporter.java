package org.hongxi.jaws.rpc;

/**
 * Created by shenhongxi on 2021/4/21.
 */
public abstract class AbstractExporter<T> extends AbstractNode implements Exporter<T> {
    protected Provider<T> provider;

    public AbstractExporter(Provider<T> provider, URL url) {
        super(url);
        this.provider = provider;
    }

    public Provider<T> getProvider() {
        return provider;
    }

    @Override
    public String desc() {
        return "[" + this.getClass().getSimpleName() + "] url=" + url;
    }

    /**
     * update real listened port
     * @param port
     */
    protected void updateRealServerPort(int port){
        getUrl().setPort(port);
    }
}