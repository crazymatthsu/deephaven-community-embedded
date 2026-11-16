package io.github.cmh.dhc.embed.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.cmh.dhc.embed.EmbedSettings;
import io.github.cmh.dhc.embed.EmbeddedDeephaven;
import io.github.cmh.dhc.embed.EmbeddedDeephavenException;
import io.github.cmh.dhc.embed.UpstreamSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** T1: what the starter does before any engine exists. */
class SettingsFromEnvironmentTest {

    private final EmbeddedDeephavenAutoConfiguration configuration = new EmbeddedDeephavenAutoConfiguration();

    /** An environment like a Spring Boot application's: -D over environment variables over application.properties. */
    private static StandardEnvironment environment(Map<String, Object> systemProperties, Map<String, Object> variables,
            Map<String, Object> applicationProperties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().forEach(source -> environment.getPropertySources().remove(source.getName()));
        environment.getPropertySources().addLast(new MapPropertySource("systemProperties", systemProperties));
        environment.getPropertySources().addLast(new SystemEnvironmentPropertySource("systemEnvironment", variables));
        environment.getPropertySources().addLast(new MapPropertySource("application.properties", applicationProperties));
        return environment;
    }

    @Test
    void applicationPropertiesAreTheDefaults() {
        EmbedSettings settings = configuration.embedSettings(environment(Map.of(), Map.of(), Map.of(
                "dhc.app.id", "dhc-b", "dhc.app.modules", "dhc-b", "dhc.http.port", "10001")));

        assertEquals("dhc-b", settings.appId());
        assertEquals(List.of("dhc-b"), settings.appModules());
        assertEquals(10001, settings.httpPort());
        assertEquals("localhost", settings.httpHost());
        assertEquals(EmbedSettings.AuthMode.PSK, settings.authMode(), "the safe default");
    }

    @Test
    void theEnvironmentOverridesThemAndASystemPropertyOverridesBoth() {
        Map<String, Object> application = Map.of("dhc.app.id", "dhc-b", "dhc.http.port", "10001", "dhc.http.host", "localhost");

        EmbedSettings fromVariable = configuration.embedSettings(environment(Map.of(),
                Map.of("DHC_HTTP_PORT", "10501", "DHC_AUTH_MODE", "anonymous"), application));
        assertEquals(10501, fromVariable.httpPort());
        assertEquals(EmbedSettings.AuthMode.ANONYMOUS, fromVariable.authMode());

        EmbedSettings fromProperty = configuration.embedSettings(environment(Map.of("dhc.http.port", "10901"),
                Map.of("DHC_HTTP_PORT", "10501"), application));
        assertEquals(10901, fromProperty.httpPort());
    }

    @Test
    void anApplicationsOwnSettingsAndItsUpstreamAreFoundTheSameWay() {
        EmbedSettings settings = configuration.embedSettings(environment(Map.of(),
                Map.of("DHC_UPSTREAM_DHC_A_PSK", "from-the-environment"),
                Map.of("dhc.app.id", "dhc-b", "dhc.upstream.dhc.a.url", "dh+plain://a.example:10000")));

        UpstreamSettings upstream = UpstreamSettings.from(settings, "dhc-a");
        assertEquals("dh+plain://a.example:10000", upstream.url());
        assertEquals(Optional.of("from-the-environment"), upstream.preSharedKey());
    }

    @Test
    void aMissingApplicationIdSaysWhatToSet() {
        EmbeddedDeephavenException error = assertThrows(EmbeddedDeephavenException.class,
                () -> configuration.embedSettings(environment(Map.of(), Map.of(), Map.of())));
        assertTrue(error.getMessage().contains("dhc.app.id is not set"), error.getMessage());
    }

    @Test
    void aWrongSettingIsReportedByItsEnvironmentName() {
        EmbeddedDeephavenException error = assertThrows(EmbeddedDeephavenException.class,
                () -> configuration.embedSettings(environment(Map.of(), Map.of(),
                        Map.of("dhc.app.id", "dhc-b", "dhc.http.port", "ten thousand"))));
        assertTrue(error.getMessage().contains("DHC_HTTP_PORT must be a port number"), error.getMessage());
    }

    @Test
    void aFailedStartBecomesAReportThatIsTheMessage() {
        EmbeddedDeephavenException cause = new EmbeddedDeephavenException("Port 10001 on localhost cannot be used.");
        FailureAnalysis analysis = new EmbeddedDeephavenFailureAnalyzer()
                .analyze(new IllegalStateException("Failed to start bean 'embeddedDeephavenLifecycle'", cause));

        assertEquals("The embedded Deephaven did not start: Port 10001 on localhost cannot be used.",
                analysis.getDescription());
        assertTrue(analysis.getAction().contains("DHC_<NAME>"), analysis.getAction());
    }

    @Test
    void anUpstreamIsDownUntilDeephavenHasStarted() {
        EmbedSettings settings = EmbedSettings.builder("dhc-b").build();
        EmbeddedDeephaven notStarted = EmbeddedDeephaven.builder()
                .settings(settings)
                .upstream(new UpstreamSettings("dhc-a", "dh+plain://localhost:10000", Optional.empty(),
                        java.time.Duration.ofSeconds(1)))
                .build();

        assertEquals(java.util.Set.of("dhc-a"), notStarted.upstreamIds());
        Health upstream = new UpstreamHealthIndicator(notStarted, "dhc-a").health();
        assertEquals(Status.DOWN, upstream.getStatus());
        assertEquals("NOT_STARTED", upstream.getDetails().get("state"));
        assertEquals(Status.DOWN, new DeephavenHealthIndicator(notStarted).health().getStatus());
    }

    private static Path configDirectory(Path directory) throws IOException {
        Files.createDirectories(directory.resolve("apps"));
        Files.createDirectories(directory.resolve("env"));
        Files.writeString(directory.resolve("defaults.env"),
                "DHC_HTTP_HOST=localhost\nDHC_HTTP_PORT=10000\nDHC_WEB_PORT=8080\nDHC_AUTH_MODE=psk\nDHC_GEN_START_TIME=\n");
        Files.writeString(directory.resolve("apps/dhc-b.env"), "DHC_HTTP_PORT=10001\nDHC_WEB_PORT=8081\n");
        Files.writeString(directory.resolve("env/uat.env"),
                "DHC_HTTP_HOST=0.0.0.0\nDHC_DHC_A_HTTP_PORT=11000\nDHC_DHC_B_HTTP_PORT=11001\nDHC_DHC_B_WEB_PORT=9081\n");
        return directory;
    }

    private EmbedSettings withTheStarter(StandardEnvironment environment) {
        new DhcSettingsEnvironmentPostProcessor().postProcessEnvironment(environment, null);
        return configuration.embedSettings(environment);
    }

    @Test
    void theConfigurationDirectoryStandsBetweenApplicationPropertiesAndTheEnvironment(@TempDir Path directory)
            throws IOException {
        String configDir = configDirectory(directory).toString();
        Map<String, Object> application = Map.of("dhc.app.id", "dhc-b", "dhc.http.port", "1", "dhc.auth.mode", "anonymous",
                "server.port", "${dhc.web.port:8081}");

        StandardEnvironment files = environment(Map.of(), Map.of("DHC_CONFIG_DIR", configDir, "DHC_ENV", "uat"), application);
        EmbedSettings fromFiles = withTheStarter(files);
        assertEquals(11001, fromFiles.httpPort(), "the environment file, scoped to dhc-b, over application.properties");
        assertEquals("0.0.0.0", fromFiles.httpHost());
        assertEquals(EmbedSettings.AuthMode.PSK, fromFiles.authMode(), "defaults.env over application.properties");
        // A placeholder follows the same rules, so Spring's own port comes from the files too.
        assertEquals("9081", files.getProperty("server.port"));
        assertEquals(Optional.empty(), fromFiles.value("DHC_GEN_START_TIME"), "an empty value is not a value");

        EmbedSettings fromVariable = withTheStarter(environment(Map.of(),
                Map.of("DHC_CONFIG_DIR", configDir, "DHC_ENV", "uat", "DHC_HTTP_PORT", "12000"), application));
        assertEquals(12000, fromVariable.httpPort(), "the environment over the files");

        EmbedSettings fromProperty = withTheStarter(environment(Map.of("dhc.http.port", "13000"),
                Map.of("DHC_CONFIG_DIR", configDir, "DHC_ENV", "uat", "DHC_HTTP_PORT", "12000"), application));
        assertEquals(13000, fromProperty.httpPort(), "-D over everything");
    }

    @Test
    void inTheEnvironmentAScopedKeyWinsOverThePlainOne() {
        EmbedSettings settings = withTheStarter(environment(Map.of(),
                Map.of("DHC_HTTP_PORT", "12000", "DHC_DHC_B_HTTP_PORT", "12001", "DHC_DHC_A_HTTP_PORT", "12999",
                        "DHC_DHC_B_AUTH_PSK", "key-for-b"),
                Map.of("dhc.app.id", "dhc-b")));

        assertEquals(12001, settings.httpPort());
        assertEquals(Optional.of("key-for-b"), settings.preSharedKey());
    }

    @Test
    void aWrongEnvironmentNameSaysWhichThereAre(@TempDir Path directory) throws IOException {
        String configDir = configDirectory(directory).toString();
        EmbeddedDeephavenException error = assertThrows(EmbeddedDeephavenException.class,
                () -> withTheStarter(environment(Map.of(), Map.of("DHC_CONFIG_DIR", configDir, "DHC_ENV", "staging"),
                        Map.of("dhc.app.id", "dhc-b"))));
        assertTrue(error.getMessage().contains("DHC_ENV is 'staging'"), error.getMessage());
    }
}
