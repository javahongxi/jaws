package org.hongxi.jaws.cluster.support;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.cluster.Cluster;
import org.hongxi.jaws.cluster.HaStrategy;
import org.hongxi.jaws.cluster.loadbalance.AbstractLoadBalance;
import org.hongxi.jaws.cluster.LoadBalance;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.util.CollectionUtils;
import org.hongxi.jaws.common.util.StringTools;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.protocol.ProtocolFilterWrapper;
import org.hongxi.jaws.registry.NotifyListener;
import org.hongxi.jaws.registry.Registry;
import org.hongxi.jaws.registry.RegistryFactory;
import org.hongxi.jaws.rpc.Protocol;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * Notify cluster the references have changed.
 * <p>
 * Created by shenhongxi on 2021/4/23.
 */
public class ClusterSupport<T> implements NotifyListener {

    private static final Logger log = LoggerFactory.getLogger(ClusterSupport.class);

    private Cluster<T> cluster;
    private final Class<T> interfaceClass;
    private final URL url;
    private final URL subscribeUrl;
    private final List<URL> registryUrls;
    private final Protocol protocol;
    private final ConcurrentMap<URL, List<Reference<T>>> registryReferences = new ConcurrentHashMap<>();
    private DynamicConfigRouter<T> dynamicConfigRouter;

    public ClusterSupport(Class<T> interfaceClass, URL refUrl, List<URL> registryUrls) {
        this.interfaceClass = interfaceClass;
        this.url = refUrl;
        this.subscribeUrl = buildSubscribeUrl(refUrl);
        this.registryUrls = registryUrls;
        protocol = new ProtocolFilterWrapper(ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(url.getProtocol()));
    }

    public void init() {
        long start = System.currentTimeMillis();
        prepareCluster();

        for (URL ru : registryUrls) {
            String directUrlStr = ru.getParameter(URLParamType.directUrl.getName());
            // 如果有directUrl，直接使用这些directUrls进行初始化，不用到注册中心discover
            if (StringUtils.isNotBlank(directUrlStr)) {
                List<URL> directUrls = parseDirectUrls(directUrlStr);
                if (!directUrls.isEmpty()) {
                    notify(ru, directUrls);
                    log.info("Use direct urls, refUrl={}, directUrls={}", url, directUrls);
                    continue;
                }
            }

            // client 注册自己，同时订阅service列表
            Registry registry = getRegistry(ru);
            registry.subscribe(subscribeUrl, this);

            // Immediately discover existing instances since the subscribe
            // notification is asynchronous and may not arrive before we check.
            List<URL> discovered = registry.discover(subscribeUrl);
            if (!CollectionUtils.isEmpty(discovered)) {
                notify(ru, discovered);
            }
        }

        boolean check = Boolean.parseBoolean(url.getParameter(URLParamType.check.getName(), URLParamType.check.value()));
        if (!CollectionUtils.isEmpty(cluster.getReferences()) || !check) {
            cluster.init();

            if (CollectionUtils.isEmpty(cluster.getReferences()) && !check) {
                log.warn("refer {} no services", this.url.getPath() + "/" + this.url.getVersion());
            }
            log.info("cluster init cost {}, refer size: {}, cluster: {}",
                    System.currentTimeMillis() - start,
                    cluster.getReferences() == null ? 0 : cluster.getReferences().size(),
                    cluster.getUrl().toSimpleString());
            return;
        }

        throw new JawsFrameworkException(String.format("ClusterSupport No service urls for the refer:%s, registries:%s",
                this.url.getIdentity(), registryUrls), JawsErrorMsgConstants.SERVICE_NOT_FOUND);
    }

    public void destroy() {
        for (URL ru : registryUrls) {
            try {
                Registry registry = getRegistry(ru);
                registry.unsubscribe(subscribeUrl, this);
                if (!JawsConstants.NODE_TYPE_REFERENCE.equals(url.getParameter(URLParamType.nodeType.getName()))) {
                    registry.unregister(url);
                }
            } catch (Exception e) {
                log.warn("Unregister or unsubscribe false for url {}, registry= {}", url, ru.getIdentity(), e);
            }
        }
        if (cluster != null) {
            try {
                cluster.destroy();
            } catch (Exception e) {
                log.warn("Exception when destroy cluster: {}", cluster.getUrl());
            }
        }
        if (dynamicConfigRouter != null) {
            dynamicConfigRouter.destroy();
        }
    }

    protected Registry getRegistry(URL url) {
        RegistryFactory registryFactory = ExtensionLoader.getExtensionLoader(RegistryFactory.class).getExtension(url.getProtocol());
        return registryFactory.getRegistry(url);
    }

    private static URL buildSubscribeUrl(URL url) {
        URL subUrl = url.createCopy();
        subUrl.addParameter(URLParamType.nodeType.getName(), JawsConstants.NODE_TYPE_SERVICE);
        return subUrl;
    }

    /**
     * <pre>
     * 1 notify的执行需要串行
     * 2 notify通知都是全量通知，在设入新的reference后，cluster需要把不再使用的reference进行回收，避免资源泄漏;
     * 3 如果该registry对应的reference数量为0，而没有其他可用的references，那就忽略该次通知；
     * </pre>
     */
    @Override
    public synchronized void notify(URL registryUrl, List<URL> urls) {
        if (CollectionUtils.isEmpty(urls)) {
            onRegistryEmpty(registryUrl);
            log.warn("ClusterSupport config change notify, urls is empty: registry={} service={} urls=[]", registryUrl.getUri(),
                    url.getIdentity());
            return;
        }

        log.info("ClusterSupport config change notify: registry={} service={} urls={}", registryUrl.getUri(), url.getIdentity(),
                getIdentities(urls));

        // 通知都是全量通知，在设入新的reference后，cluster内部需要把不再使用的reference进行回收，避免资源泄漏
        // ////////////////////////////////////////////////////////////////////////////////

        // 判断urls中是否包含权重信息，并通知 LoadBalance
        List<URL> serviceUrls = processWeights(urls);
        doRefreshReferencesByUrls(registryUrl, serviceUrls);
    }

    private void doRefreshReferencesByUrls(URL registryUrl, List<URL> serviceUrls) {
        List<Reference<T>> newReferences = new ArrayList<>();
        for (URL u : serviceUrls) {
            if (!u.canServe(url)) {
                continue;
            }
            Reference<T> reference = getExistingReference(u, registryReferences.get(registryUrl));
            if (reference == null) {
                // careful u: serverURL, referenceURL的配置会被serverURL的配置覆盖
                URL referenceURL = u.createCopy();
                mergeClientConfigs(referenceURL);
                reference = protocol.refer(interfaceClass, referenceURL);
            }
            if (reference != null) {
                newReferences.add(reference);
            }
        }

        if (CollectionUtils.isEmpty(newReferences)) {
            onRegistryEmpty(registryUrl);
            return;
        }

        // 此处不销毁references，由cluster进行销毁
        registryReferences.put(registryUrl, newReferences);
        refreshCluster();
    }

    /**
     * Check whether the first url carries weight (rule) information.
     * If so, extract the weight string, notify LoadBalance, and return the remaining urls;
     * otherwise return the original list unchanged.
     *
     * @param urls the raw notification urls (must not be mutated)
     * @return the urls after stripping the weight rule url, or the original list
     */
    private List<URL> processWeights(List<URL> urls) {
        if (urls == null || urls.isEmpty()) {
            return urls;
        }
        URL ruleUrl = urls.get(0);
        // 没有权重时需要传递默认值。因为可能是变更时去掉了权重
        String weights = URLParamType.weights.value();
        if ("rule".equalsIgnoreCase(ruleUrl.getProtocol())) {
            weights = ruleUrl.getParameter(URLParamType.weights.getName(), URLParamType.weights.value());
            urls = urls.subList(1, urls.size());
        }
        log.info("refresh weight. weight={}", weights);
        this.cluster.getLoadBalance().setWeightString(weights);
        return urls;
    }

    private void onRegistryEmpty(URL excludeRegistryUrl) {
        boolean noMoreOtherRefers = registryReferences.size() == 1 && registryReferences.containsKey(excludeRegistryUrl);
        if (noMoreOtherRefers) {
            log.warn(String.format("Ignore notify for no more references in this cluster, registry: %s, cluster=%s",
                    excludeRegistryUrl, getUrl()));
        } else {
            registryReferences.remove(excludeRegistryUrl);
            refreshCluster();
        }
    }

    private Reference<T> getExistingReference(URL url, List<Reference<T>> references) {
        if (references == null) {
            return null;
        }
        for (Reference<T> r : references) {
            if (Objects.equals(url, r.getUrl()) || Objects.equals(url, r.getServiceUrl())) {
                return r;
            }
        }
        return null;
    }

    /**
     * referenceURL的扩展参数中，除了application、module外，其他参数被client覆盖， 如果client没有则使用reference的参数
     *
     * @param referenceURL
     */
    private void mergeClientConfigs(URL referenceURL) {
        String application = referenceURL.getParameter(URLParamType.application.getName(), URLParamType.application.value());
        String module = referenceURL.getParameter(URLParamType.module.getName(), URLParamType.module.value());
        referenceURL.addParameters(this.url.getParameters());

        referenceURL.addParameter(URLParamType.application.getName(), application);
        referenceURL.addParameter(URLParamType.module.getName(), module);
    }

    private void refreshCluster() {
        List<Reference<T>> references = new ArrayList<>();
        for (List<Reference<T>> refs : registryReferences.values()) {
            references.addAll(refs);
        }
        cluster.onRefresh(references);
    }

    public Cluster<T> getCluster() {
        return cluster;
    }

    public URL getUrl() {
        return url;
    }

    private String getIdentities(List<URL> urls) {
        if (urls == null || urls.isEmpty()) {
            return "[]";
        }
        StringJoiner sj = new StringJoiner(",", "[", "]");
        for (URL u : urls) {
            sj.add(u.getIdentity());
        }
        return sj.toString();
    }

    private void prepareCluster() {
        String clusterName = url.getParameter(URLParamType.cluster.getName(), URLParamType.cluster.value());
        String loadBalanceName = url.getParameter(URLParamType.loadBalance.getName(), URLParamType.loadBalance.value());
        String haStrategyName = url.getParameter(URLParamType.haStrategy.getName(), URLParamType.haStrategy.value());

        // noinspection unchecked
        cluster = ExtensionLoader.getExtensionLoader(Cluster.class).getExtension(clusterName);
        // noinspection unchecked
        LoadBalance<T> loadBalance = ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(loadBalanceName);
        // noinspection unchecked
        HaStrategy<T> ha = ExtensionLoader.getExtensionLoader(HaStrategy.class).getExtension(haStrategyName);
        ha.setUrl(url);
        cluster.setLoadBalance(loadBalance);
        cluster.setHaStrategy(ha);
        cluster.setUrl(url);

        // Register DynamicConfigRouter for dynamic routing rule support
        if (loadBalance instanceof AbstractLoadBalance) {
            dynamicConfigRouter = new DynamicConfigRouter<>(interfaceClass.getName());
            ((AbstractLoadBalance<T>) loadBalance).addRouter(dynamicConfigRouter);
        }
    }

    private List<URL> parseDirectUrls(String directUrlStr) {
        String[] durlArr = JawsConstants.COMMA_SPLIT_PATTERN.split(directUrlStr);
        List<URL> directUrls = new ArrayList<>();
        for (String dus : durlArr) {
            URL du = URL.valueOf(StringTools.urlDecode(dus));
            directUrls.add(du);
        }
        return directUrls;
    }
}