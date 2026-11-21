package org.hongxi.jaws.metadata;

import org.hongxi.jaws.rpc.URL;

import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Application-level metadata describing all services exported by one application,
 * the counterpart of Dubbo's {@code MetadataInfo} in local metadata mode.
 * <p>
 * The content is a map from service key (group/path/version) to the full provider
 * URL string, so a consumer can rebuild interface-level URLs verbatim. The
 * {@link #revision} is a content hash (MD5 over the sorted URL strings): instances
 * exporting the same service list compute the same revision, which lets consumers
 * group instances and fetch metadata once per revision instead of once per instance.
 * <p>
 * Instances are treated as immutable value objects once published; mutators are
 * only used while building the next revision.
 */
public class MetadataInfo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String application;

    /** Content hash of {@link #services}, see {@link #calRevision()}. */
    private String revision;

    /** Service key (group/path/version) to full provider URL string. */
    private Map<String, String> services = new TreeMap<>();

    public MetadataInfo() {
    }

    public MetadataInfo(String application) {
        this.application = application;
    }

    /**
     * Service identity within an application: group, path and version, without
     * host/port so that all instances of the application share one entry.
     */
    public static String serviceKey(URL url) {
        return url.getGroup() + "/" + url.getPath() + "/" + url.getVersion();
    }

    public void addServiceUrl(URL url) {
        services.put(serviceKey(url), url.toFullStr());
    }

    public void removeServiceUrl(URL url) {
        services.remove(serviceKey(url));
    }

    /**
     * Compute the revision from the current content. Order-independent: the URL
     * strings are sorted before hashing, so insertion order never changes the result.
     */
    public String calRevision() {
        return calRevision(services);
    }

    public static String calRevision(Map<String, String> services) {
        StringBuilder builder = new StringBuilder();
        new TreeMap<>(services).values().forEach(value -> builder.append(value).append('\n'));
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(builder.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 is required by every JRE spec
            throw new IllegalStateException("MD5 algorithm not available", e);
        }
    }

    /**
     * Stamp the revision from the current content; call after all mutators.
     */
    public void init() {
        this.revision = calRevision();
    }

    public String getApplication() {
        return application;
    }

    public void setApplication(String application) {
        this.application = application;
    }

    public String getRevision() {
        return revision;
    }

    public void setRevision(String revision) {
        this.revision = revision;
    }

    public Map<String, String> getServices() {
        return services;
    }

    public void setServices(Map<String, String> services) {
        this.services = services;
    }

    @Override
    public String toString() {
        return "MetadataInfo{application=" + application + ", revision=" + revision
                + ", serviceCount=" + (services == null ? 0 : services.size()) + "}";
    }
}