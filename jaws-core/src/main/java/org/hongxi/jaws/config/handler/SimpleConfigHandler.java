package org.hongxi.jaws.config.handler;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.exception.JawsErrorMsg;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.protocol.support.ProtocolFilterDecorator;
import org.hongxi.jaws.registry.Registry;
import org.hongxi.jaws.registry.RegistryFactory;
import org.hongxi.jaws.rpc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;

/**
 * Created by shenhongxi on 2021/3/6.
 */
@SpiMeta(name = JawsConstants.DEFAULT_VALUE)
public class SimpleConfigHandler implements ConfigHandler {

    private static final Logger logger = LoggerFactory.getLogger(SimpleConfigHandler.class);

    @Override
    public <T> Exporter<T> export(Class<T> interfaceClass, T ref, List<URL> registryUrls, URL serviceUrl) {
        // export service
        // 利用protocol decorator来增加filter特性
        String protocolName = serviceUrl.getParameter(URLParamType.protocol.getName(), URLParamType.protocol.value());
        Protocol orgProtocol = ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(protocolName);
        Provider<T> provider = getProvider(orgProtocol, ref, serviceUrl, interfaceClass);

        Protocol protocol = new ProtocolFilterDecorator(orgProtocol);
        Exporter<T> exporter = protocol.export(provider, serviceUrl);

        // register service
        register(registryUrls, serviceUrl);

        return exporter;
    }

    protected <T> Provider<T> getProvider(Protocol protocol, T proxyImpl, URL url, Class<T> clz){
        if (protocol instanceof ProviderFactory){
            return ((ProviderFactory) protocol).newProvider(proxyImpl, url, clz);
        } else{
            return new DefaultProvider<T>(proxyImpl, url, clz);
        }
    }

    @Override
    public <T> void unexport(List<Exporter<T>> exporters, Collection<URL> registryUrls) {
        for (Exporter<T> exporter : exporters) {
            try {
                unRegister(registryUrls, exporter.getUrl());
                exporter.unexport();
            } catch (Exception e) {
                logger.warn("Exception when unexport exporters: {}", exporters);
            }
        }
    }

    private void register(List<URL> registryUrls, URL serviceUrl) {
        for (URL url : registryUrls) {
            // 根据check参数的设置，register失败可能会抛异常，上层应该知晓
            RegistryFactory registryFactory = ExtensionLoader.getExtensionLoader(RegistryFactory.class).getExtension(url.getProtocol());
            if (registryFactory == null) {
                throw new JawsFrameworkException(new JawsErrorMsg(500, JawsErrorMsgConstants.FRAMEWORK_REGISTER_ERROR_CODE,
                        "register error! Could not find extension for registry protocol:" + url.getProtocol()
                                + ", make sure registry module for " + url.getProtocol() + " is in classpath!"));
            }
            Registry registry = registryFactory.getRegistry(url);
            registry.register(serviceUrl);
        }
    }

    private void unRegister(Collection<URL> registryUrls, URL serviceUrl) {
        for (URL url : registryUrls) {
            // 不管check的设置如何，做完所有unregistry，做好清理工作
            try {
                RegistryFactory registryFactory = ExtensionLoader.getExtensionLoader(RegistryFactory.class).getExtension(url.getProtocol());
                Registry registry = registryFactory.getRegistry(url);
                registry.unregister(serviceUrl);
            } catch (Exception e) {
                logger.warn("unregister url false: {}", url, e);
            }
        }
    }
}