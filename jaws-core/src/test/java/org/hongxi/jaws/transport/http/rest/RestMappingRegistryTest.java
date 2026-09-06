package org.hongxi.jaws.transport.http.rest;

import io.netty.handler.codec.http.HttpMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RestMappingRegistry}.
 */
class RestMappingRegistryTest {

    private RestMappingRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new RestMappingRegistry();
    }

    @Test
    void emptyRegistryReturnsNoMatch() {
        assertTrue(registry.isEmpty());
        assertTrue(registry.match(HttpMethod.GET, "/users/1").isEmpty());
    }

    @Test
    void matchByHttpMethodAndPath() throws Exception {
        Method method = String.class.getMethod("length");
        RestMapping mapping = new RestMapping(
                HttpMethod.GET,
                new PathPattern("/users/{id}"),
                "java.lang.String",
                "length",
                method,
                Collections.emptyList()
        );
        registry.addMapping(mapping);

        assertFalse(registry.isEmpty());

        // matching
        Optional<RestMappingRegistry.MatchedMapping> result = registry.match(HttpMethod.GET, "/users/42");
        assertTrue(result.isPresent());
        assertEquals("42", result.get().getPathVariables().get("id"));
        assertEquals("length", result.get().getMapping().getMethodName());

        // wrong HTTP method
        assertTrue(registry.match(HttpMethod.POST, "/users/42").isEmpty());

        // wrong path
        assertTrue(registry.match(HttpMethod.GET, "/orders/42").isEmpty());
    }

    @Test
    void firstMatchWins() throws Exception {
        Method method = String.class.getMethod("length");
        RestMapping mapping1 = new RestMapping(
                HttpMethod.GET,
                new PathPattern("/users/{id}"),
                "java.lang.String",
                "length",
                method,
                Collections.emptyList()
        );
        RestMapping mapping2 = new RestMapping(
                HttpMethod.GET,
                new PathPattern("/users/**"),
                "java.lang.String",
                "length",
                method,
                Collections.emptyList()
        );
        registry.addMapping(mapping1);
        registry.addMapping(mapping2);

        // /users/42 should match the first (more specific) pattern
        Optional<RestMappingRegistry.MatchedMapping> result = registry.match(HttpMethod.GET, "/users/42");
        assertTrue(result.isPresent());
        assertEquals("/users/{id}", result.get().getMapping().getPathPattern().getPattern());
    }

    @Test
    void multipleMethodsOnSamePath() throws Exception {
        Method method = String.class.getMethod("length");
        RestMapping getMapping = new RestMapping(
                HttpMethod.GET,
                new PathPattern("/users/{id}"),
                "java.lang.String",
                "getUser",
                method,
                Collections.emptyList()
        );
        RestMapping deleteMapping = new RestMapping(
                HttpMethod.DELETE,
                new PathPattern("/users/{id}"),
                "java.lang.String",
                "deleteUser",
                method,
                Collections.emptyList()
        );
        registry.addMapping(getMapping);
        registry.addMapping(deleteMapping);

        Optional<RestMappingRegistry.MatchedMapping> getResult = registry.match(HttpMethod.GET, "/users/1");
        assertTrue(getResult.isPresent());
        assertEquals("getUser", getResult.get().getMapping().getMethodName());

        Optional<RestMappingRegistry.MatchedMapping> deleteResult = registry.match(HttpMethod.DELETE, "/users/1");
        assertTrue(deleteResult.isPresent());
        assertEquals("deleteUser", deleteResult.get().getMapping().getMethodName());
    }
}
