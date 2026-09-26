package org.hongxi.jaws.spring.boot;

import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.spring.boot.annotation.JawsReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Placeholder handling in {@link ReferenceAnnotationBeanPostProcessor}: every
 * String-valued {@code @JawsReference} attribute is resolved against the Spring
 * Environment, and an unwired resolver must not swallow the raw value.
 */
class ReferenceAnnotationBeanPostProcessorPlaceholderTest {

    interface DemoService {
    }

    static class Holder {

        @JawsReference(directUrl = "${sample.wire.address}", group = "${svc.group}",
                version = "${svc.version}", application = "${svc.app}")
        DemoService everyAttribute;

        @JawsReference(directUrl = "${sample.wire.address}")
        DemoService onlyDirectUrl;

        @JawsReference(directUrl = "${missing.key}")
        DemoService unresolvable;

        @JawsReference(generic = true, serviceInterface = "${generic.iface}")
        DemoService genericInterface;
    }

    private GenericApplicationContext context;
    private StandardEnvironment environment;
    private ReferenceAnnotationBeanPostProcessor processor;

    @BeforeEach
    void setUp() {
        Map<String, Object> props = new HashMap<>();
        props.put("sample.wire.address", "127.0.0.1:50061");
        props.put("svc.group", "gray");
        props.put("svc.version", "2.0.0");
        props.put("svc.app", "wire-boot-consumer");
        props.put("generic.iface", "org.example.OrphanService");

        environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", props));
        context = new GenericApplicationContext();
        context.setEnvironment(environment);
        context.refresh();

        processor = new ReferenceAnnotationBeanPostProcessor(context);
        // A refreshed context seeds its embedded-value chain with exactly this
        // resolver (non-strict: unknown keys come back as literal text)
        processor.setEmbeddedValueResolver(environment::resolvePlaceholders);
    }

    @AfterEach
    void tearDown() {
        processor.destroy();
        context.close();
    }

    private ReferenceConfig<Object> map(String field) throws NoSuchFieldException {
        JawsReference jawsRef = Holder.class.getDeclaredField(field).getAnnotation(JawsReference.class);
        JawsProperties properties = new JawsProperties();
        properties.getApplication().setName("global-app");
        properties.getReference().setGroup("global-group");
        properties.getReference().setVersion("global-version");
        return processor.toReferenceConfig(jawsRef, DemoService.class, properties,
                new ProtocolConfig(), new RegistryConfig());
    }

    @Test
    void everyStringAttributeIsResolved() throws Exception {
        ReferenceConfig<Object> ref = map("everyAttribute");

        assertEquals("127.0.0.1:50061", ref.getDirectUrl());
        assertEquals("gray", ref.getGroup(), "group must go through the resolver like directUrl");
        assertEquals("2.0.0", ref.getVersion(), "version must go through the resolver like directUrl");
        assertEquals("wire-boot-consumer", ref.getApplication(),
                "application must go through the resolver like directUrl");
    }

    @Test
    void blankAttributesFallBackToGlobalProperties() throws Exception {
        ReferenceConfig<Object> ref = map("onlyDirectUrl");

        assertEquals("127.0.0.1:50061", ref.getDirectUrl());
        assertEquals("global-group", ref.getGroup());
        assertEquals("global-version", ref.getVersion());
        assertEquals("global-app", ref.getApplication());
    }

    @Test
    void genericServiceInterfaceIsResolved() throws Exception {
        ReferenceConfig<Object> ref = map("genericInterface");

        assertEquals("org.example.OrphanService", ref.getServiceInterface(),
                "a generic reference named through a property must resolve too");
    }

    @Test
    void unresolvablePlaceholderIsKeptLiteral() throws Exception {
        // Spring's embedded resolver is non-strict, so an unknown key arrives as the
        // raw text; the failure then surfaces where the URL is parsed, not silently
        assertEquals("${missing.key}", map("unresolvable").getDirectUrl());
    }

    @Test
    void rawValuePassesThroughWhenNoResolverIsWired() throws Exception {
        ReferenceAnnotationBeanPostProcessor unwired =
                new ReferenceAnnotationBeanPostProcessor(context);
        JawsReference jawsRef = Holder.class.getDeclaredField("everyAttribute")
                .getAnnotation(JawsReference.class);

        JawsProperties properties = new JawsProperties();
        properties.getApplication().setName("global-app");
        ReferenceConfig<Object> ref = unwired.toReferenceConfig(jawsRef, DemoService.class,
                properties, new ProtocolConfig(), new RegistryConfig());

        assertEquals("${sample.wire.address}", ref.getDirectUrl(),
                "without a resolver the guard must return the value untouched, not null it away");
    }
}
