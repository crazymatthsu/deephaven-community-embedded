package io.github.cmh.dhc.embed.spring;

import io.github.cmh.dhc.embed.EmbeddedDeephaven;
import org.springframework.context.SmartLifecycle;

/**
 * Starts the embedded Deephaven with the application context and stops it with it.
 *
 * <p>The phase puts it ahead of Spring Boot's web server: Deephaven is up before the first HTTP
 * request can arrive, and is still up while the last ones are answered. Every bean exists by the
 * time it starts, so whatever the scripts read through {@code host} is there.
 */
public final class EmbeddedDeephavenLifecycle implements SmartLifecycle {

    /**
     * Spring Boot starts its web server at {@code DEFAULT_PHASE - 2048} and begins a graceful
     * shutdown at {@code DEFAULT_PHASE - 1024}. A lower phase starts earlier and stops later.
     */
    public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

    private final EmbeddedDeephaven deephaven;

    public EmbeddedDeephavenLifecycle(EmbeddedDeephaven deephaven) {
        this.deephaven = deephaven;
    }

    @Override
    public void start() {
        deephaven.start();
    }

    @Override
    public void stop() {
        deephaven.stop();
    }

    @Override
    public boolean isRunning() {
        return deephaven.isRunning();
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
