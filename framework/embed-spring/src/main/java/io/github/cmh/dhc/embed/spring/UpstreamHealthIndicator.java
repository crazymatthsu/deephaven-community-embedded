package io.github.cmh.dhc.embed.spring;

import io.github.cmh.dhc.embed.EmbeddedDeephaven;
import io.github.cmh.dhc.embed.RemoteTables;
import java.util.Optional;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * One upstream in {@code /actuator/health}, under the upstream's id: up while its tables are
 * subscribed, down while it is away or not connected yet. The application stays up either way;
 * its tables hold their last rows until the upstream is back.
 */
public final class UpstreamHealthIndicator implements HealthIndicator {

    private final EmbeddedDeephaven deephaven;
    private final String upstreamId;

    public UpstreamHealthIndicator(EmbeddedDeephaven deephaven, String upstreamId) {
        this.deephaven = deephaven;
        this.upstreamId = upstreamId;
    }

    @Override
    public Health health() {
        Optional<RemoteTables> found = deephaven.upstream(upstreamId);
        if (found.isEmpty()) {
            return Health.down().withDetail("state", "NOT_STARTED").build();
        }
        RemoteTables upstream = found.get();
        RemoteTables.State state = upstream.state();
        Health.Builder health = state == RemoteTables.State.CONNECTED ? Health.up() : Health.down();
        health.withDetail("state", state.name())
                .withDetail("url", upstream.url())
                .withDetail("since", upstream.since().toString())
                .withDetail("subscriptions", upstream.subscriptions())
                .withDetail("reconnects", upstream.reconnects());
        upstream.lastError().ifPresent(error -> health.withDetail("lastError", error));
        return health.build();
    }
}
