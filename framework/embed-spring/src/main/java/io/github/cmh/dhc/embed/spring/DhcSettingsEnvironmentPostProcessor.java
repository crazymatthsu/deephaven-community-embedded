package io.github.cmh.dhc.embed.spring;

import io.github.cmh.dhc.embed.ConfigDirectory;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Gives a Spring host the two things a plain Java host gets from {@code EmbedSettings.load}: the
 * configuration directory, and keys scoped to the application.
 *
 * <ul>
 * <li><b>The configuration directory</b> ({@code DHC_CONFIG_DIR}, {@code DHC_ENV}) becomes a
 * property source just below the environment variables and above {@code application.properties}:
 * the files override what the application ships with, and the process overrides the files.</li>
 * <li><b>Scoped keys</b>: asked for {@code dhc.http.port}, each property source is first asked for
 * {@code dhc.dhc.b.http.port} (for the application {@code dhc-b}). So {@code DHC_DHC_B_HTTP_PORT}
 * in the environment wins over {@code DHC_HTTP_PORT} there, as it does in a plain Java host.</li>
 * </ul>
 *
 * <p>Because both work at the level of Spring's {@code Environment}, they hold for placeholders
 * too: {@code server.port=${dhc.web.port}} follows the same rules as the embedded Deephaven's own
 * settings.
 */
public final class DhcSettingsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String FILES_SOURCE = "dhcConfigDirectory";
    static final String SCOPED_SOURCE = "dhcScopedSettings";

    @Override
    public int getOrder() {
        // After application.properties has been read: dhc.app.id is usually there.
        return ConfigDataEnvironmentPostProcessor.ORDER + 10;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String appId = environment.getProperty(EmbeddedDeephavenAutoConfiguration.APP_ID);
        if (appId == null || appId.isBlank()) {
            return; // not an embedded Deephaven application, or one the auto-configuration will refuse
        }
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(FILES_SOURCE);
        sources.remove(SCOPED_SOURCE);

        String directory = environment.getProperty(propertyName(ConfigDirectory.CONFIG_DIR));
        if (directory != null && !directory.isBlank()) {
            Optional<String> name = Optional.ofNullable(environment.getProperty(propertyName(ConfigDirectory.ENV)))
                    .filter(text -> !text.isBlank());
            Map<String, Object> files = new LinkedHashMap<>();
            ConfigDirectory.load(Path.of(directory.trim()), appId.trim(), name.map(String::trim)).forEach((key, value) -> {
                if (!value.isBlank()) { // an empty value means "not set here"
                    files.put(propertyName(key), value);
                }
            });
            MapPropertySource source = new MapPropertySource(FILES_SOURCE, files);
            if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
                sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source);
            } else {
                sources.addFirst(source);
            }
        }
        sources.addFirst(new ScopedSettings(appId.trim(), sources));
    }

    /** {@code DHC_HTTP_PORT} to {@code dhc.http.port}. */
    static String propertyName(String key) {
        return key.toLowerCase(Locale.ROOT).replace('_', '.');
    }

    /**
     * Answers {@code dhc.*} from the other property sources, in their order, asking each for the
     * key scoped to this application before the plain one.
     */
    private static final class ScopedSettings extends PropertySource<MutablePropertySources> {

        /** Spring Boot's own view over all sources, this one included: asking it would ask this source again. */
        private static final String SPRING_BOOT_VIEW = "configurationProperties";

        private final String scope;
        private final ThreadLocal<Boolean> answering = ThreadLocal.withInitial(() -> false);

        ScopedSettings(String appId, MutablePropertySources sources) {
            super(SCOPED_SOURCE, sources);
            this.scope = "dhc." + appId.toLowerCase(Locale.ROOT).replace('-', '.') + ".";
        }

        @Override
        public Object getProperty(String name) {
            if (!name.startsWith("dhc.") || name.startsWith(scope)) {
                return null;
            }
            if (answering.get()) {
                return null;
            }
            answering.set(true);
            try {
                String scoped = scope + name.substring("dhc.".length());
                for (PropertySource<?> other : getSource()) {
                    if (other == this || SPRING_BOOT_VIEW.equals(other.getName())) {
                        continue;
                    }
                    Object value = other.getProperty(scoped);
                    if (value == null) {
                        value = other.getProperty(name);
                    }
                    if (value != null && !(value instanceof String text && text.isBlank())) {
                        return value;
                    }
                }
                return null;
            } finally {
                answering.set(false);
            }
        }
    }
}
