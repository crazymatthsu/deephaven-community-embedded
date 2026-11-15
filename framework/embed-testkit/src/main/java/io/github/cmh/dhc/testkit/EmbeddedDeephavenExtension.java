package io.github.cmh.dhc.testkit;

import io.deephaven.engine.table.Table;
import io.github.cmh.dhc.embed.EmbedSettings;
import io.github.cmh.dhc.embed.EmbeddedDeephaven;
import io.github.cmh.dhc.embed.Engine;
import io.github.cmh.dhc.embed.SideEffectPolicy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;

/**
 * Starts one embedded Deephaven for a test class and stops it when the test JVM is done.
 *
 * <pre>{@code
 * @RegisterExtension
 * static final EmbeddedDeephavenExtension deephaven =
 *         EmbeddedDeephavenExtension.forApp("dhc-a").modules("hello").bind("greeting", "hi").build();
 *
 * @Test
 * void helloTicks() {
 *     TableAssertions.awaitSize(deephaven.get(), deephaven.table("hello"), 2, Duration.ofSeconds(20));
 * }
 * }</pre>
 *
 * <p>The instance listens on a free port of localhost, keeps its files in a fresh temporary
 * directory and leaves the process alone ({@link SideEffectPolicy#hostKeepsControl()}), so a
 * failing test fails the test and does not exit the JVM. Deephaven is one per JVM and cannot be
 * restarted (F10); the build gives every test class its own JVM for that reason.
 */
public final class EmbeddedDeephavenExtension implements BeforeAllCallback {

    private static final Namespace NAMESPACE = Namespace.create(EmbeddedDeephavenExtension.class);

    private final String appId;
    private final List<String> modules;
    private final Map<String, Object> bindings;
    private volatile EmbeddedDeephaven deephaven;

    private EmbeddedDeephavenExtension(Builder builder) {
        this.appId = builder.appId;
        this.modules = List.copyOf(builder.modules);
        this.bindings = new LinkedHashMap<>(builder.bindings);
    }

    public static Builder forApp(String appId) {
        return new Builder(appId);
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        // Kept in the root store: started once, closed by JUnit when the whole run ends.
        Running running = context.getRoot().getStore(NAMESPACE)
                .getOrComputeIfAbsent(Running.class, key -> new Running(start()), Running.class);
        deephaven = running.deephaven;
    }

    private EmbeddedDeephaven start() {
        EmbedSettings settings;
        try {
            settings = EmbedSettings.builder(appId)
                    .httpHost("localhost")
                    .httpPort(0)
                    .authMode(EmbedSettings.AuthMode.ANONYMOUS)
                    .appModules(modules)
                    .workDir(Files.createTempDirectory("dhc-test-" + appId + "-"))
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        EmbeddedDeephaven.Builder builder = EmbeddedDeephaven.builder()
                .settings(settings)
                .sideEffects(SideEffectPolicy.hostKeepsControl());
        bindings.forEach(builder::bind);
        EmbeddedDeephaven started = builder.build();
        started.start();
        return started;
    }

    /** The running instance. */
    public EmbeddedDeephaven get() {
        EmbeddedDeephaven current = deephaven;
        if (current == null) {
            throw new IllegalStateException("The extension has not started Deephaven yet. Register it on a static"
                    + " field with @RegisterExtension.");
        }
        return current;
    }

    public Table table(String name) {
        return get().table(name);
    }

    public Engine engine() {
        return get().engine();
    }

    private static final class Running implements AutoCloseable {
        private final EmbeddedDeephaven deephaven;

        Running(EmbeddedDeephaven deephaven) {
            this.deephaven = deephaven;
        }

        @Override
        public void close() {
            deephaven.stop();
        }
    }

    public static final class Builder {
        private final String appId;
        private List<String> modules = List.of();
        private final Map<String, Object> bindings = new LinkedHashMap<>();

        private Builder(String appId) {
            this.appId = appId;
        }

        /** The application-mode modules to run, from the test classpath. */
        public Builder modules(String... modules) {
            this.modules = List.of(modules);
            return this;
        }

        /** A host object for the scripts, as {@code host.<name>}. */
        public Builder bind(String name, Object object) {
            bindings.put(name, object);
            return this;
        }

        public EmbeddedDeephavenExtension build() {
            return new EmbeddedDeephavenExtension(this);
        }
    }
}
