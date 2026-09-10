package org.hongxi.jaws.admin;

import org.hongxi.jaws.admin.config.AdminConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Jaws Admin — standalone console for the Harbor cluster.
 *
 * @author shenhongxi
 */
@SpringBootApplication
@EnableConfigurationProperties(AdminConfig.class)
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}
