package io.github.cmh.dhc.embed.spring;

import io.github.cmh.dhc.embed.EmbeddedDeephaven;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Health indicators, for a host that has Spring Boot's actuator: {@code deephaven}, and one per
 * upstream under the upstream's id ({@code dhc-a}). {@code management.health.deephaven.enabled=false}
 * turns them off.
 */
@AutoConfiguration(after = EmbeddedDeephavenAutoConfiguration.class)
@ConditionalOnClass({HealthIndicator.class, ConditionalOnEnabledHealthIndicator.class})
@ConditionalOnBean(EmbeddedDeephaven.class)
@ConditionalOnEnabledHealthIndicator("deephaven")
public class EmbeddedDeephavenHealthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "deephavenHealthIndicator")
    DeephavenHealthIndicator deephavenHealthIndicator(EmbeddedDeephaven deephaven) {
        return new DeephavenHealthIndicator(deephaven);
    }

    /**
     * The upstreams are known only once the customizers have run, so their indicators cannot be
     * bean definitions. They are added to the actuator's registry when every bean exists.
     */
    @Bean
    SmartInitializingSingleton upstreamHealthIndicators(EmbeddedDeephaven deephaven,
            ObjectProvider<HealthContributorRegistry> registry) {
        return () -> registry.ifAvailable(contributors -> deephaven.upstreamIds().forEach(
                id -> contributors.registerContributor(id, new UpstreamHealthIndicator(deephaven, id))));
    }
}
