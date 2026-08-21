package org.hongxi.jaws.sample.gray.provider;

import org.hongxi.jaws.spring.boot.annotation.EnableJaws;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Gray release provider - configure tag via {@code jaws.service.tag} property.
 * <p>
 * Run two instances with different tags:
 * <pre>
 * # Stable instance (default, no tag)
 * java -jar jaws-sample-gray-provider.jar --server.port=8090 --jaws.protocol.port=10000
 *
 * # Gray instance
 * java -jar jaws-sample-gray-provider.jar --server.port=8091 --jaws.protocol.port=10001 --jaws.service.tag=gray
 * </pre>
 */
@EnableJaws
@SpringBootApplication
public class GrayProviderApplication {
    public static void main(String[] args) {
        SpringApplication.run(GrayProviderApplication.class, args);
    }
}
