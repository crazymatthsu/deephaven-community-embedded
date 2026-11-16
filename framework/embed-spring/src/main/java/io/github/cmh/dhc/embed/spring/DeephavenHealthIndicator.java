package io.github.cmh.dhc.embed.spring;

import io.github.cmh.dhc.embed.EmbeddedDeephaven;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/** {@code deephaven} in {@code /actuator/health}: up while the embedded instance is running. */
public final class DeephavenHealthIndicator implements HealthIndicator {

    private final EmbeddedDeephaven deephaven;

    public DeephavenHealthIndicator(EmbeddedDeephaven deephaven) {
        this.deephaven = deephaven;
    }

    @Override
    public Health health() {
        Health.Builder health = deephaven.isRunning() ? Health.up() : Health.down();
        health.withDetail("appId", deephaven.settings().appId());
        if (deephaven.isRunning()) {
            health.withDetail("port", deephaven.httpPort())
                    .withDetail("authentication", deephaven.settings().authMode().name().toLowerCase())
                    .withDetail("tables", deephaven.tables().size());
        }
        Throwable fatal = deephaven.fatalError();
        if (fatal != null) {
            health.withDetail("fatalError", String.valueOf(fatal));
        }
        return health.build();
    }
}
