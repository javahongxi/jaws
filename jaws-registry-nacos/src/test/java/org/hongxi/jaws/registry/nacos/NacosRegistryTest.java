package org.hongxi.jaws.registry.nacos;

import com.alibaba.nacos.api.naming.pojo.Instance;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The URL ↔ instance mapping of the nacos leg, asserted without a server.
 * <p>
 * Mirror image of {@code HarborRegistryTest}: both legs write the same
 * coordinates and the same sentinel keys onto the same kind of payload, so
 * moving a service from one leg to the other is a config change rather than a
 * data migration. The literals are asserted here and there on purpose instead
 * of being shared — that duplication is the invariant, and it is what turns
 * one test red when either side renames something.
 * <p>
 * Deliberately not asserted: {@code healthy}/{@code ephemeral} on the
 * outbound payload. Both pojos already default to {@code true}, so an
 * assertion there cannot fail and would only pretend to pin the setter.
 *
 * @author shenhongxi
 */
class NacosRegistryTest {

    private static final String SERVICE_PATH = "org.hongxi.jaws.sample.api.DemoService";

    /**
     * close() would reach for the NamingService this mapping test never needs,
     * and a real one is not cheap to build, so the service stays null.
     */
    private final MappingOnlyRegistry registry = new MappingOnlyRegistry(registryUrl());

    // ------------------------------------------------------------------
    // Coordinates: URL -> (serviceName, group)
    // ------------------------------------------------------------------

    @Test
    void serviceNameIsNamespaceQualifiedPath() {
        // The separator is spelled out rather than taken from
        // JawsConstants.PATH_SEPARATOR — a constant that is a literal "/"
        // precisely because a service name is a wire identifier: a provider
        // started on Windows must still be findable by a consumer on Linux.
        // If that constant ever goes back to File.separator, this test turns
        // red on Windows; that silent discovery failure is what it guards.
        assertEquals("jaws/" + SERVICE_PATH, NacosPathUtils.toServiceName(providerUrl()));
    }

    @Test
    void groupComesFromUrlParameterAndFallsBackToItsDefault() {
        assertEquals("gray", NacosPathUtils.toGroup(
                providerUrl(withParam(UrlParam.Identity.GROUP.getName(), "gray"))));
        // An unset group is not null: URL getters fall back to the UrlParam
        // default, and both legs have to agree on that default to meet in the
        // same namespace.
        assertEquals("default_rpc", NacosPathUtils.toGroup(providerUrl(new HashMap<>())));
    }

    // ------------------------------------------------------------------
    // Payload: URL -> Instance
    // ------------------------------------------------------------------

    @Test
    void registerPayloadCarriesCoordinatesParametersAndSentinels() {
        Instance instance = NacosRegistry.toInstance(
                providerUrl(withParam(UrlParam.Identity.VERSION.getName(), "2.0")));

        assertEquals("10.0.0.1", instance.getIp());
        assertEquals(20881, instance.getPort());
        assertEquals("jaws", instance.getMetadata().get("protocol"));
        assertEquals(SERVICE_PATH, instance.getMetadata().get("path"));
        assertEquals("2.0", instance.getMetadata().get(UrlParam.Identity.VERSION.getName()));
    }

    // ------------------------------------------------------------------
    // Rebuild: Instance -> URL
    // ------------------------------------------------------------------

    @Test
    void metadataWinsOverTheReferenceUrl() {
        Instance instance = new Instance();
        instance.setIp("10.0.0.1");
        instance.setPort(20881);
        instance.setMetadata(Map.of("protocol", "wire", "path", "com.acme.Other"));

        List<URL> urls = registry.instancesToUrls(referenceUrl(), List.of(instance));

        assertEquals(1, urls.size());
        URL url = urls.get(0);
        assertEquals("wire", url.getProtocol());
        assertEquals("com.acme.Other", url.getPath());
        assertEquals("10.0.0.1", url.getHost());
        assertEquals(20881, url.getPort());
        // The consumer's own tuning must not masquerade as provider metadata:
        // the metadata branch takes its parameters from the payload alone.
        assertNull(url.getParameter("sticky"));
    }

    @Test
    void foreignInstanceIsRebuiltFromTheReferenceInsteadOfDropped() {
        // The nacos pojo initialises metadata to an empty map, so a payload
        // that says nothing about jaws arrives in two shapes: untouched, or
        // explicitly null from a deserialised document. Both must fall back
        // rather than disappear.
        Instance untouched = new Instance();
        untouched.setIp("10.0.0.2");
        untouched.setPort(20882);

        Instance nullMetadata = new Instance();
        nullMetadata.setIp("10.0.0.3");
        nullMetadata.setPort(20883);
        nullMetadata.setMetadata(null);

        List<URL> urls = registry.instancesToUrls(referenceUrl(),
                List.of(untouched, nullMetadata));

        assertEquals(2, urls.size());
        for (URL url : urls) {
            assertEquals("jaws", url.getProtocol());
            assertEquals(SERVICE_PATH, url.getPath());
        }
        assertEquals("10.0.0.2:20882", urls.get(0).getHostPort());
        assertEquals("10.0.0.3:20883", urls.get(1).getHostPort());
        // The fallback copies the reference wholesale: the consumer's own
        // tuning rides along with it.
        assertEquals("from-consumer", urls.get(0).getParameter("sticky"));
    }

    @Test
    void absentInstanceListIsAnEmptyAddressList() {
        assertTrue(registry.instancesToUrls(referenceUrl(), List.of()).isEmpty());
        assertTrue(registry.instancesToUrls(referenceUrl(), null).isEmpty());
    }

    // ------------------------------------------------------------------
    // Round trip
    // ------------------------------------------------------------------

    @Test
    void roundTripYieldsTheRegisteredUrl() {
        URL provider = providerUrl(withParam(UrlParam.Identity.GROUP.getName(), "gray"));

        List<URL> discovered = registry.instancesToUrls(provider,
                List.of(NacosRegistry.toInstance(provider)));

        assertEquals(1, discovered.size());
        URL url = discovered.get(0);
        assertEquals(provider.getProtocol(), url.getProtocol());
        assertEquals(provider.getHostPort(), url.getHostPort());
        assertEquals(provider.getPath(), url.getPath());
        assertEquals(provider.getGroup(), url.getGroup());

        // The sentinel keys exist only to carry the two fields Instance has no
        // column for. They must not survive into the parameter table: URL
        // equals covers parameters (see URL.java), so a provider that
        // registered one URL must not be handed back a different one.
        assertNull(url.getParameter("protocol"));
        assertNull(url.getParameter("path"));
        assertEquals(provider, url);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static URL providerUrl() {
        return providerUrl(new HashMap<>());
    }

    private static URL providerUrl(Map<String, String> parameters) {
        return new URL("jaws", "10.0.0.1", 20881, SERVICE_PATH, parameters);
    }

    /** Consumer side of the mapping: same service, its own coordinates. */
    private static URL referenceUrl() {
        Map<String, String> parameters = new HashMap<>();
        parameters.put("sticky", "from-consumer");
        return new URL("jaws", "10.0.0.9", 9000, SERVICE_PATH, parameters);
    }

    private static URL registryUrl() {
        Map<String, String> parameters = new HashMap<>();
        // Long period: this test never drives the failback scheduler.
        parameters.put(UrlParam.Registry.RETRY_PERIOD.getName(), "60000");
        return new URL("jaws", "127.0.0.1", 2181, "registry", parameters);
    }

    private static Map<String, String> withParam(String name, String value) {
        Map<String, String> parameters = new HashMap<>();
        parameters.put(name, value);
        return parameters;
    }

    private static class MappingOnlyRegistry extends NacosRegistry {
        MappingOnlyRegistry(URL url) {
            super(url, null);
        }

        @Override
        public void close() {
            // Nothing was opened, and the null NamingService must not be
            // touched.
        }
    }
}
