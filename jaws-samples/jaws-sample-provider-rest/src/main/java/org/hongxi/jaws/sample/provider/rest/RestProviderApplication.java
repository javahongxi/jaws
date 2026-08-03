package org.hongxi.jaws.sample.provider.rest;

import org.hongxi.jaws.spring.boot.annotation.EnableJaws;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * REST Provider sample application.
 * <p>
 * Starts a Jaws RPC provider with REST API enabled.
 * All exported Jaws services are automatically exposed via REST endpoints
 * at the configured path (default: /rest).
 * <p>
 * Usage:
 * <ol>
 *   <li>Run this application</li>
 *   <li>REST endpoint available at http://localhost:8083/rest</li>
 *   <li>Use curl or any HTTP client to discover and invoke services</li>
 * </ol>
 * <p>
 * Examples:
 * <pre>
 * # List all services
 * curl http://localhost:8083/rest/services
 *
 * # Get service methods
 * curl http://localhost:8083/rest/services/org.hongxi.jaws.sample.api.DemoService
 *
 * # Invoke a method
 * curl -X POST http://localhost:8083/rest/invoke/org.hongxi.jaws.sample.api.DemoService/hello \
 *   -H "Content-Type: application/json" \
 *   -d '{"arg0": "World"}'
 * </pre>
 */
@EnableJaws
@SpringBootApplication
public class RestProviderApplication {

    public static void main(String[] args) {
        SpringApplication.run(RestProviderApplication.class, args);
    }
}
