package org.hongxi.jaws.metadata;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.serialization.FastJson2Serialization;
import org.hongxi.jaws.serialization.Hessian2Serialization;
import org.hongxi.jaws.serialization.ObjectInput;
import org.hongxi.jaws.serialization.ObjectOutput;
import org.hongxi.jaws.serialization.Serialization;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Content-addressing invariants of {@link MetadataInfo}: revision stability,
 * order independence, content sensitivity, and wire round-trips through the
 * two default serializers.
 */
class MetadataInfoTest {

    private URL serviceUrl(String iface, int port) {
        Map<String, String> params = new HashMap<>();
        params.put(UrlParam.Identity.ENDPOINT_TYPE.getName(), JawsConstants.ENDPOINT_TYPE_SERVICE);
        return new URL("jaws", "10.0.0.1", port, iface, params);
    }

    @Test
    void revisionIsStableAcrossCalls() {
        MetadataInfo info = new MetadataInfo("app-a");
        info.addServiceUrl(serviceUrl("com.example.DemoService", 8080));
        info.init();
        assertEquals(info.getRevision(), info.calRevision());
        assertEquals(32, info.getRevision().length(), "MD5 hex is 32 chars");
    }

    @Test
    void revisionIsOrderIndependent() {
        MetadataInfo a = new MetadataInfo("app-a");
        a.addServiceUrl(serviceUrl("com.example.DemoService", 8080));
        a.addServiceUrl(serviceUrl("com.example.OrderService", 8080));
        a.init();

        MetadataInfo b = new MetadataInfo("app-a");
        b.addServiceUrl(serviceUrl("com.example.OrderService", 8080));
        b.addServiceUrl(serviceUrl("com.example.DemoService", 8080));
        b.init();

        assertEquals(a.getRevision(), b.getRevision(),
                "same service list must hash to the same revision regardless of export order");
    }

    @Test
    void revisionChangesWithContent() {
        MetadataInfo a = new MetadataInfo("app-a");
        a.addServiceUrl(serviceUrl("com.example.DemoService", 8080));
        a.init();

        MetadataInfo b = new MetadataInfo("app-a");
        b.addServiceUrl(serviceUrl("com.example.DemoService", 8081));
        b.init();

        assertNotEquals(a.getRevision(), b.getRevision());
    }

    @Test
    void serviceKeyCarriesGroupPathVersion() {
        URL url = serviceUrl("com.example.DemoService", 8080);
        assertEquals(url.getGroup() + "/com.example.DemoService/" + url.getVersion(),
                MetadataInfo.serviceKey(url));
    }

    @Test
    void addAndRemoveServiceUrl() {
        MetadataInfo info = new MetadataInfo("app-a");
        URL url = serviceUrl("com.example.DemoService", 8080);
        info.addServiceUrl(url);
        assertEquals(1, info.getServices().size());
        assertTrue(info.getServices().containsValue(url.toFullStr()));

        info.removeServiceUrl(url);
        assertTrue(info.getServices().isEmpty());
    }

    @Test
    void hessian2Roundtrip() throws Exception {
        assertRoundtrip(new Hessian2Serialization());
    }

    @Test
    void fastjson2Roundtrip() throws Exception {
        assertRoundtrip(new FastJson2Serialization());
    }

    private void assertRoundtrip(Serialization serialization) throws Exception {
        MetadataInfo info = new MetadataInfo("app-a");
        info.addServiceUrl(serviceUrl("com.example.DemoService", 8080));
        info.addServiceUrl(serviceUrl("com.example.OrderService", 8080));
        info.init();

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutput out = serialization.serialize(bos);
        out.writeObject(info);
        out.flush();

        ObjectInput in = serialization.deserialize(new ByteArrayInputStream(bos.toByteArray()));
        MetadataInfo restored = in.readObject(MetadataInfo.class);

        assertEquals(info.getApplication(), restored.getApplication());
        assertEquals(info.getRevision(), restored.getRevision());
        assertEquals(info.getServices(), restored.getServices());
        assertEquals(info.getRevision(), restored.calRevision(),
                "content hash must survive the wire unchanged");
    }
}