package org.hongxi.jaws.transport.http.rest;

import io.netty.handler.codec.http.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Thread-safe registry of {@link RestMapping} entries.
 * <p>
 * During service export, {@link RestAnnotationScanner} populates this registry
 * with mappings derived from Spring Web / JAX-RS annotations. At runtime,
 * {@code HttpRequestHandler} calls {@link #match(HttpMethod, String)} to find
 * the first matching mapping for an incoming HTTP request.
 *
 * @author shenhongxi
 */
public class RestMappingRegistry {

    private static final Logger log = LoggerFactory.getLogger(RestMappingRegistry.class);

    private final List<RestMapping> mappings = new ArrayList<>();

    /**
     * Register a new REST mapping.
     */
    public void addMapping(RestMapping mapping) {
        mappings.add(mapping);
        log.info("registered REST mapping: {}", mapping);
    }

    /**
     * Find the first mapping that matches the given HTTP method and path.
     *
     * @param httpMethod the HTTP method (GET, POST, etc.)
     * @param path       the request path (without query string)
     * @return an optional containing the match result (mapping + path variables),
     *         or empty if no mapping matched
     */
    public Optional<MatchedMapping> match(HttpMethod httpMethod, String path) {
        for (RestMapping mapping : mappings) {
            if (!mapping.getHttpMethod().equals(httpMethod)) {
                continue;
            }
            PathPattern.MatchResult result = mapping.getPathPattern().match(path);
            if (result != null) {
                return Optional.of(new MatchedMapping(mapping, result.getPathVariables()));
            }
        }
        return Optional.empty();
    }

    /**
     * @return an unmodifiable view of all registered mappings
     */
    public List<RestMapping> getMappings() {
        return Collections.unmodifiableList(mappings);
    }

    /**
     * @return true if no mappings have been registered
     */
    public boolean isEmpty() {
        return mappings.isEmpty();
    }

    /**
     * Holds a matched mapping together with the extracted path variables.
     */
    public static class MatchedMapping {
        private final RestMapping mapping;
        private final java.util.Map<String, String> pathVariables;

        public MatchedMapping(RestMapping mapping, java.util.Map<String, String> pathVariables) {
            this.mapping = mapping;
            this.pathVariables = pathVariables;
        }

        public RestMapping getMapping() {
            return mapping;
        }

        public java.util.Map<String, String> getPathVariables() {
            return pathVariables;
        }
    }
}
