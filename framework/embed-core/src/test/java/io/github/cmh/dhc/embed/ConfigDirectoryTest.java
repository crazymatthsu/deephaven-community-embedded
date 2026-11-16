package io.github.cmh.dhc.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T1: settings from the configuration directory, and where they stand among the other sources. */
class ConfigDirectoryTest {

    @TempDir
    Path directory;

    @BeforeEach
    void write() throws IOException {
        file("defaults.env", """
                # every key
                DHC_HTTP_HOST=localhost
                DHC_HTTP_PORT=10000
                DHC_AUTH_MODE=psk
                DHC_APP_MODULES=
                DHC_GEN_RATE=5
                """);
        file("apps/dhc-b.env", """
                DHC_APP_MODULES=dhc-b
                DHC_HTTP_PORT=10001
                DHC_UPSTREAM_DHC_A_URL=dh+plain://localhost:10000
                """);
        file("env/uat.env", """
                DHC_AUTH_MODE=psk
                DHC_HTTP_HOST=0.0.0.0
                DHC_DHC_A_HTTP_PORT=11000
                DHC_DHC_B_HTTP_PORT=11001
                DHC_DHC_B_UPSTREAM_DHC_A_URL=dh+plain://a.uat:11000
                """);
        file("env/dev.env", "DHC_AUTH_MODE=anonymous\n");
    }

    private void file(String name, String content) throws IOException {
        Path file = directory.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private EmbedSettings load(String appId, Map<String, String> appDefaults, Map<String, String> environment,
            Map<String, String> systemProperties) {
        return EmbedSettings.load(appId, appDefaults, environment, systemProperties::get);
    }

    @Test
    void scopedKeysAreNamedAfterTheApplication() {
        assertEquals("DHC_DHC_B_HTTP_PORT", ConfigDirectory.scopedKey("dhc-b", "DHC_HTTP_PORT"));
        assertEquals("DHC_DHC_B_UPSTREAM_DHC_A_URL", ConfigDirectory.scopedKey("dhc-b", "DHC_UPSTREAM_DHC_A_URL"));
    }

    @Test
    void laterFilesOverrideEarlierOnesAndScopedKeysAreResolved() {
        Map<String, String> uat = ConfigDirectory.load(directory, "dhc-b", Optional.of("uat"));

        assertEquals("11001", uat.get("DHC_HTTP_PORT"), "env file, scoped to dhc-b");
        assertEquals("0.0.0.0", uat.get("DHC_HTTP_HOST"), "env file");
        assertEquals("dhc-b", uat.get("DHC_APP_MODULES"), "application file");
        assertEquals("dh+plain://a.uat:11000", uat.get("DHC_UPSTREAM_DHC_A_URL"));
        assertEquals("5", uat.get("DHC_GEN_RATE"), "defaults");
        assertFalse(uat.containsKey("DHC_DHC_B_HTTP_PORT"), "the scoped key was not resolved: " + uat);
    }

    @Test
    void withoutAnEnvironmentTheDefaultsAndTheApplicationFileApply() {
        Map<String, String> settings = ConfigDirectory.load(directory, "dhc-b", Optional.empty());

        assertEquals("10001", settings.get("DHC_HTTP_PORT"));
        assertEquals("localhost", settings.get("DHC_HTTP_HOST"));
    }

    @Test
    void theDirectoryStandsBetweenTheApplicationsDefaultsAndTheEnvironment() {
        Map<String, String> where = Map.of("DHC_CONFIG_DIR", directory.toString(), "DHC_ENV", "uat");

        EmbedSettings fromFiles = load("dhc-b", Map.of("DHC_HTTP_PORT", "1", "DHC_APP_MODULES", "x"), where, Map.of());
        assertEquals(11001, fromFiles.httpPort(), "the files win over the application's own defaults");
        assertEquals(List.of("dhc-b"), fromFiles.appModules());
        assertEquals("0.0.0.0", fromFiles.httpHost());
        assertEquals(Optional.of("dh+plain://a.uat:11000"), fromFiles.value("DHC_UPSTREAM_DHC_A_URL"));

        EmbedSettings fromEnvironment = load("dhc-b", Map.of(),
                Map.of("DHC_CONFIG_DIR", directory.toString(), "DHC_ENV", "uat", "DHC_HTTP_PORT", "12000"), Map.of());
        assertEquals(12000, fromEnvironment.httpPort(), "the environment wins over the files, scoped or not");

        EmbedSettings fromProperty = load("dhc-b", Map.of(), where, Map.of("dhc.http.port", "13000"));
        assertEquals(13000, fromProperty.httpPort(), "-D wins over everything");
    }

    @Test
    void inTheEnvironmentAScopedKeyWinsOverThePlainOne() {
        Map<String, String> environment = Map.of("DHC_HTTP_PORT", "12000", "DHC_DHC_B_HTTP_PORT", "12001",
                "DHC_DHC_A_HTTP_PORT", "12999", "DHC_DHC_B_AUTH_PSK", "key-for-b", "DHC_AUTH_PSK", "key-for-everyone");

        EmbedSettings b = load("dhc-b", Map.of(), environment, Map.of());
        assertEquals(12001, b.httpPort());
        assertEquals(Optional.of("key-for-b"), b.preSharedKey());

        EmbedSettings c = load("dhc-c", Map.of(), environment, Map.of());
        assertEquals(12000, c.httpPort(), "another application sees only the plain key");
        assertEquals(Optional.of("key-for-everyone"), c.preSharedKey());
    }

    @Test
    void withoutADirectoryNothingChanges() {
        EmbedSettings settings = load("dhc-b", Map.of("DHC_HTTP_PORT", "10001"), Map.of(), Map.of());
        assertEquals(10001, settings.httpPort());
    }

    @Test
    void aWrongDirectoryOrEnvironmentSaysWhatIsThere() {
        EmbeddedDeephavenException noDirectory = assertThrows(EmbeddedDeephavenException.class,
                () -> load("dhc-b", Map.of(), Map.of("DHC_CONFIG_DIR", directory.resolve("nowhere").toString()), Map.of()));
        assertTrue(noDirectory.getMessage().contains("has no defaults.env"), noDirectory.getMessage());

        EmbeddedDeephavenException noEnvironment = assertThrows(EmbeddedDeephavenException.class,
                () -> load("dhc-b", Map.of(), Map.of("DHC_CONFIG_DIR", directory.toString(), "DHC_ENV", "staging"), Map.of()));
        assertTrue(noEnvironment.getMessage().contains("DHC_ENV is 'staging'"), noEnvironment.getMessage());
        assertTrue(noEnvironment.getMessage().contains("[dev, uat]"), noEnvironment.getMessage());
    }

    @Test
    void aLineThatIsNotASettingIsRefusedWithItsPlace() throws IOException {
        file("env/dev.env", "DHC_AUTH_MODE=anonymous\nexport FOO=bar\n");

        EmbeddedDeephavenException error = assertThrows(EmbeddedDeephavenException.class,
                () -> ConfigDirectory.load(directory, "dhc-b", Optional.of("dev")));
        assertTrue(error.getMessage().contains("dev.env line 2"), error.getMessage());
    }

    @Test
    void theRepositorysOwnDirectoryLoadsForEveryApplicationAndEnvironment() {
        Path repository = Path.of(System.getProperty("dhc.repo.config"));
        for (String environment : List.of("dev", "uat", "prod")) {
            Map<String, String> a = ConfigDirectory.load(repository, "dhc-a", Optional.of(environment));
            Map<String, String> b = ConfigDirectory.load(repository, "dhc-b", Optional.of(environment));
            assertEquals("dhc-a", a.get("DHC_APP_MODULES"));
            assertEquals("dhc-b", b.get("DHC_APP_MODULES"));
            assertTrue(b.get("DHC_UPSTREAM_DHC_A_URL").endsWith(":" + a.get("DHC_HTTP_PORT")),
                    environment + ": B subscribes to " + b.get("DHC_UPSTREAM_DHC_A_URL") + " but A listens on "
                            + a.get("DHC_HTTP_PORT"));
        }
    }
}
