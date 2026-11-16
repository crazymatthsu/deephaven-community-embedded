package io.github.cmh.dhc.apps.dhcb.spring;

import io.github.cmh.dhc.apps.dhcb.RoleB;
import io.github.cmh.dhc.embed.spring.EmbeddedDeephavenCustomizer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Host: a Spring Boot application with Deephaven inside its JVM, running role B. Two ports:
 * Spring MVC on 8081 (REST, health, the page) and Deephaven's own server on 10001 (web IDE, API).
 * Instance A has to be reachable; this application waits for it at start.
 *
 * <p>The embedded Deephaven itself comes from {@code embed-spring}; {@code application.properties}
 * says which module it runs and where A is.
 */
@SpringBootApplication
public class DhcBSpringApp {

    public static void main(String[] args) {
        SpringApplication.run(DhcBSpringApp.class, args);
    }

    /** Role B's one addition to the instance: where A is. */
    @Bean
    EmbeddedDeephavenCustomizer roleB() {
        return RoleB::bind;
    }
}
