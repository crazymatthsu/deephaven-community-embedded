package io.github.cmh.dhc.apps.dhca.spring;

import io.github.cmh.dhc.apps.dhca.RoleA;
import io.github.cmh.dhc.apps.dhca.RoleASettings;
import io.github.cmh.dhc.embed.EmbedSettings;
import io.github.cmh.dhc.embed.spring.EmbeddedDeephavenCustomizer;
import io.github.cmh.dhc.embed.spring.EmbeddedDeephavenLifecycle;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;

/**
 * Host: a Spring Boot application with Deephaven inside its JVM, running role A. Two ports:
 * Spring MVC on 8080 (REST from the application's own order cache, health) and Deephaven's own
 * server on 10000 (web IDE, API, and what instance B subscribes to).
 *
 * <p>The role is the same object a plain Java host creates. Here Spring owns it: it is a bean,
 * its bindings reach the embedded Deephaven through a customizer, and its feed starts once
 * Deephaven is up and stops before Deephaven does.
 */
@SpringBootApplication
public class DhcASpringApp {

    public static void main(String[] args) {
        SpringApplication.run(DhcASpringApp.class, args);
    }

    /** The mock feed and the order cache it updates. Closed with the context. */
    @Bean
    RoleA roleA(EmbedSettings settings) {
        return RoleA.create(RoleASettings.from(settings));
    }

    /** What the module reads as {@code host.execFeed}, {@code host.orderCache} and {@code host.settings}. */
    @Bean
    EmbeddedDeephavenCustomizer roleABindings(RoleA role) {
        return (builder, settings) -> role.bind(builder);
    }

    /** Executions begin to arrive when the tables that show them exist, and end before those are gone. */
    @Bean
    SmartLifecycle roleAFeed(RoleA role) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                role.start();
                running = true;
            }

            @Override
            public void stop() {
                running = false;
                role.close();
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public int getPhase() {
                // Just after the embedded Deephaven on the way up, just before it on the way down.
                return EmbeddedDeephavenLifecycle.PHASE + 1;
            }
        };
    }
}
