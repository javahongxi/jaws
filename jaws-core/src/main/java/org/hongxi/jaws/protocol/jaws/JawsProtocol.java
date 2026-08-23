package org.hongxi.jaws.protocol.jaws;

import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.protocol.AbstractProtocol;
import org.hongxi.jaws.rpc.Exporter;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.URL;

/**
 * Default protocol implementation of the framework, registered under the
 * {@code "jaws"} extension name, that creates {@link JawsExporter} and
 * {@link JawsReference} for service exposure and consumer reference.
 *
 * @see AbstractProtocol
 *
 * <p>
 * Created by shenhongxi on 2021/4/21.
 */
@Extension("jaws")
public class JawsProtocol extends AbstractProtocol {

    @Override
    protected <T> Exporter<T> createExporter(Provider<T> provider) {
        URL url = provider.getUrl();
        return new JawsExporter<>(provider, url);
    }

    @Override
    protected <T> Reference<T> createReference(Class<T> interfaceClass, URL url) {
        return new JawsReference<>(interfaceClass, url);
    }
}