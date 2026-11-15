package io.github.cmh.dhc.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** T1: where a setting's value comes from. No engine involved. */
class EmbedSettingsTest {

    private static final Map<String, String> NO_ENVIRONMENT = Map.of();

    private static EmbedSettings load(Map<String, String> appDefaults, Map<String, String> environment,
            Map<String, String> systemProperties) {
        return EmbedSettings.load("dhc-a", appDefaults, environment, systemProperties::get);
    }

    @Test
    void builtInDefaultsAreTheSafeOnes() {
        EmbedSettings settings = load(Map.of(), NO_ENVIRONMENT, Map.of());

        assertEquals("localhost", settings.httpHost());
        assertEquals(10000, settings.httpPort());
        assertEquals(EmbedSettings.AuthMode.PSK, settings.authMode());
        assertTrue(settings.preSharedKey().isEmpty());
        assertEquals(List.of(), settings.appModules());
        assertTrue(settings.appDir().isEmpty());
        assertTrue(settings.workDir().endsWith(Path.of("dhc-embedded", "dhc-a")), settings.workDir().toString());
    }

    @Test
    void precedenceIsDefaultsThenAppThenEnvironmentThenSystemProperty() {
        Map<String, String> app = Map.of("DHC_HTTP_PORT", "10001", "DHC_APP_MODULES", "hello");

        assertEquals(10001, load(app, NO_ENVIRONMENT, Map.of()).httpPort());
        assertEquals(10002, load(app, Map.of("DHC_HTTP_PORT", "10002"), Map.of()).httpPort());
        assertEquals(10003, load(app, Map.of("DHC_HTTP_PORT", "10002"), Map.of("dhc.http.port", "10003")).httpPort());
        assertEquals(List.of("hello"), load(app, Map.of("DHC_HTTP_PORT", "10002"), Map.of()).appModules());
    }

    @Test
    void everyKeyIsRead() {
        EmbedSettings settings = load(Map.of(), Map.of(
                "DHC_HTTP_HOST", "0.0.0.0",
                "DHC_HTTP_PORT", "0",
                "DHC_AUTH_MODE", "anonymous",
                "DHC_APP_MODULES", " hello , other ,",
                "DHC_APP_DIR", "some/dir",
                "DHC_WORK_DIR", "work"), Map.of());

        assertEquals("0.0.0.0", settings.httpHost());
        assertEquals(0, settings.httpPort());
        assertEquals(EmbedSettings.AuthMode.ANONYMOUS, settings.authMode());
        assertEquals(List.of("hello", "other"), settings.appModules());
        assertEquals(Path.of("some/dir"), settings.appDir().orElseThrow());
        assertEquals(Path.of("work"), settings.workDir());
    }

    @Test
    void aBlankValueCountsAsNotSet() {
        assertEquals(10000, load(Map.of(), Map.of("DHC_HTTP_PORT", "  "), Map.of()).httpPort());
    }

    @Test
    void badValuesAreRejectedWithTheKeyInTheMessage() {
        assertMessage("DHC_HTTP_PORT must be a port number", Map.of("DHC_HTTP_PORT", "ten thousand"));
        assertMessage("DHC_HTTP_PORT must be a port number", Map.of("DHC_HTTP_PORT", "70000"));
        assertMessage("DHC_AUTH_MODE must be psk or anonymous", Map.of("DHC_AUTH_MODE", "none"));
    }

    @Test
    void anAppDefaultHasToBeNamedLikeASetting() {
        EmbeddedDeephavenException error = assertThrows(EmbeddedDeephavenException.class,
                () -> load(Map.of("http.port", "1"), NO_ENVIRONMENT, Map.of()));

        assertTrue(error.getMessage().contains("http.port"), error.getMessage());
        assertTrue(error.getMessage().contains("DHC_<NAME>"), error.getMessage());
    }

    @Test
    void anApplicationsOwnSettingsFollowTheSamePrecedence() {
        Map<String, String> app = Map.of("DHC_GEN_RATE", "5", "DHC_GEN_SEED", "42");

        EmbedSettings settings = load(app, Map.of("DHC_GEN_RATE", "50"), Map.of("dhc.gen.rate", "500"));

        assertEquals(500.0, settings.doubleValue("DHC_GEN_RATE", 1.0));
        assertEquals(50.0, load(app, Map.of("DHC_GEN_RATE", "50"), Map.of()).doubleValue("DHC_GEN_RATE", 1.0));
        assertEquals(42, settings.longValue("DHC_GEN_SEED", 0));
        assertEquals(7, settings.longValue("DHC_GEN_ORDERS", 7));
        assertTrue(settings.value("DHC_GEN_ORDERS").isEmpty());
        // Survives a copy, and is absent from settings made by hand.
        assertEquals(42, settings.toBuilder().httpPort(1).build().longValue("DHC_GEN_SEED", 0));
        assertEquals(9, EmbedSettings.builder("dhc-a").build().longValue("DHC_GEN_SEED", 9));
    }

    @Test
    void anApplicationsOwnSettingThatIsNotANumberIsRejectedWithItsName() {
        EmbedSettings settings = load(Map.of(), Map.of("DHC_GEN_RATE", "fast"), Map.of());

        EmbeddedDeephavenException error =
                assertThrows(EmbeddedDeephavenException.class, () -> settings.doubleValue("DHC_GEN_RATE", 1.0));
        assertTrue(error.getMessage().contains("DHC_GEN_RATE must be a number"), error.getMessage());
    }

    @Test
    void thePreSharedKeyIsNeverPrinted() {
        EmbedSettings settings = load(Map.of(), Map.of("DHC_AUTH_PSK", "not-a-real-key"), Map.of());

        assertEquals("not-a-real-key", settings.preSharedKey().orElseThrow());
        assertFalse(settings.toString().contains("not-a-real-key"), settings.toString());
    }

    @Test
    void anAppIdHasToBeUsableInFileNames() {
        assertThrows(EmbeddedDeephavenException.class, () -> EmbedSettings.builder("Dhc A"));
    }

    private static void assertMessage(String expected, Map<String, String> environment) {
        EmbeddedDeephavenException error =
                assertThrows(EmbeddedDeephavenException.class, () -> load(Map.of(), environment, Map.of()));
        assertTrue(error.getMessage().contains(expected), error.getMessage());
    }
}
