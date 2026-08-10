package org.hongxi.jaws.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Jaws RPC framework.
 * <p>
 * Example YAML configuration:
 * <pre>
 * jaws:
 *   application:
 *     name: my-app
 *   group: default
 *   version: "1.0"
 *   protocol:
 *     name: jaws
 *     port: 10000
 *     serialization: fastjson2
 *     endpoint-factory: netty
 *   registry:
 *     address: nacos://127.0.0.1:8848
 *     username: nacos
 *     password: nacos
 *   service:
 *     share-channel: true
 *   reference:
 *     request-timeout: 2000
 *     check: false
 * </pre>
 * <p>
 * Created by shenhongxi on 2026/7/17.
 */
@ConfigurationProperties(JawsProperties.CONFIG_PREFIX)
public class JawsProperties {

    public static final String CONFIG_PREFIX = "jaws";

    /**
     * Application configuration.
     */
    private Application application = new Application();

    /**
     * Default service group.
     */
    private String group;

    /**
     * Default service version.
     */
    private String version;

    /**
     * Protocol configuration.
     */
    private Protocol protocol = new Protocol();

    /**
     * Registry configuration.
     */
    private Registry registry = new Registry();

    /**
     * Default service export configuration.
     */
    private Service service = new Service();

    /**
     * Default reference configuration.
     */
    private Reference reference = new Reference();

    public Application getApplication() {
        return application;
    }

    public void setApplication(Application application) {
        this.application = application;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Protocol getProtocol() {
        return protocol;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public Registry getRegistry() {
        return registry;
    }

    public void setRegistry(Registry registry) {
        this.registry = registry;
    }

    public Service getService() {
        return service;
    }

    public void setService(Service service) {
        this.service = service;
    }

    public Reference getReference() {
        return reference;
    }

    public void setReference(Reference reference) {
        this.reference = reference;
    }

    /**
     * Application configuration properties.
     */
    public static class Application {

        /**
         * Application name.
         */
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /**
     * Protocol configuration properties.
     */
    public static class Protocol {

        /**
         * Protocol name (e.g., jaws).
         */
        private String name = "jaws";

        /**
         * Service host. Usually not needed, auto-detected.
         */
        private String host;

        /**
         * Service port. Use -1 for dynamic port allocation.
         */
        private Integer port = -1;

        /**
         * Serialization type (e.g., fastjson2, hessian2).
         */
        private String serialization = "fastjson2";

        /**
         * Endpoint factory SPI name (e.g., netty).
         */
        private String endpointFactory = "netty";

        /**
         * Codec type.
         */
        private String codec;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        public String getSerialization() {
            return serialization;
        }

        public void setSerialization(String serialization) {
            this.serialization = serialization;
        }

        public String getEndpointFactory() {
            return endpointFactory;
        }

        public void setEndpointFactory(String endpointFactory) {
            this.endpointFactory = endpointFactory;
        }

        public String getCodec() {
            return codec;
        }

        public void setCodec(String codec) {
            this.codec = codec;
        }
    }

    /**
     * Registry configuration properties.
     */
    public static class Registry {

        /**
         * Registry address, supports protocol prefix and multiple addresses.
         * Format: protocol://ip1:port1,ip2:port2 (e.g., nacos://127.0.0.1:8848)
         */
        private String address = "nacos://127.0.0.1:8848";

        /**
         * Registry username (for Nacos authentication).
         */
        private String username;

        /**
         * Registry password (for Nacos authentication).
         */
        private String password;

        /**
         * Connect timeout in milliseconds.
         */
        private Integer connectTimeout;

        /**
         * Session timeout in milliseconds.
         */
        private Integer registrySessionTimeout;

        /**
         * Retry period in milliseconds.
         */
        private Integer registryRetryPeriod;

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public Integer getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Integer connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Integer getRegistrySessionTimeout() {
            return registrySessionTimeout;
        }

        public void setRegistrySessionTimeout(Integer registrySessionTimeout) {
            this.registrySessionTimeout = registrySessionTimeout;
        }

        public Integer getRegistryRetryPeriod() {
            return registryRetryPeriod;
        }

        public void setRegistryRetryPeriod(Integer registryRetryPeriod) {
            this.registryRetryPeriod = registryRetryPeriod;
        }
    }

    /**
     * Default service export configuration properties.
     */
    public static class Service {

        /**
         * Whether to share a single channel for all services on the same port.
         */
        private Boolean shareChannel = true;

        public Boolean getShareChannel() {
            return shareChannel;
        }

        public void setShareChannel(Boolean shareChannel) {
            this.shareChannel = shareChannel;
        }
    }

    /**
     * Default reference configuration properties.
     */
    public static class Reference {

        /**
         * Request timeout in milliseconds.
         */
        private Integer requestTimeout;

        /**
         * Whether to check service availability on startup.
         */
        private Boolean check;

        /**
         * Retry count on failure.
         */
        private Integer retries;

        public Integer getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Integer requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

        public Boolean getCheck() {
            return check;
        }

        public void setCheck(Boolean check) {
            this.check = check;
        }

        public Integer getRetries() {
            return retries;
        }

        public void setRetries(Integer retries) {
            this.retries = retries;
        }
    }
}
