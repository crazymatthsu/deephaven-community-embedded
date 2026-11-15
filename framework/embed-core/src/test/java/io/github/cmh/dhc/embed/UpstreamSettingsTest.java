package io.github.cmh.dhc.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** T1: where an upstream is and how to log in to it. No engine involved. */
class UpstreamSettingsTest {

    private static EmbedSettings settings(Map<String, String> environment) {
        return EmbedSettings.load("dhc-b", Map.of("DHC_UPSTREAM_DHC_A_URL", "dh+plain://localhost:10000"), environment,
                key -> null);
    }

    @Test
    void theKeysAreNamedAfterTheUpstream() {
        assertEquals("DHC_UPSTREAM_DHC_A_URL", UpstreamSettings.urlKey("dhc-a"));
        assertEquals("DHC_UPSTREAM_DHC_A_PSK", UpstreamSettings.pskKey("dhc-a"));
    }

    @Test
    void theApplicationsDefaultAddressIsUsedUnlessTheEnvironmentSaysOtherwise() {
        UpstreamSettings byDefault = UpstreamSettings.from(settings(Map.of()), "dhc-a");
        assertEquals("dh+plain://localhost:10000", byDefault.url());
        assertTrue(byDefault.preSharedKey().isEmpty());
        assertEquals(Duration.ofSeconds(60), byDefault.connectTimeout());

        UpstreamSettings configured = UpstreamSettings.from(settings(Map.of(
                "DHC_UPSTREAM_DHC_A_URL", "dh://a.example.com:443",
                "DHC_UPSTREAM_DHC_A_PSK", "not-a-real-key",
                "DHC_UPSTREAM_CONNECT_TIMEOUT_SECONDS", "5")), "dhc-a");
        assertEquals("dh://a.example.com:443", configured.url());
        assertEquals(Optional.of("not-a-real-key"), configured.preSharedKey());
        assertEquals(Duration.ofSeconds(5), configured.connectTimeout());
    }

    @Test
    void theKeyIsNeverPrinted() {
        UpstreamSettings upstream =
                new UpstreamSettings("dhc-a", "dh+plain://localhost:10000", Optional.of("not-a-real-key"), Duration.ofSeconds(1));

        assertFalse(upstream.toString().contains("not-a-real-key"), upstream.toString());
    }

    @Test
    void anUpstreamWithoutAnAddressIsReportedWithTheSettingToSet() {
        EmbedSettings none = EmbedSettings.load("dhc-b", Map.of(), Map.of(), key -> null);

        EmbeddedDeephavenException error =
                assertThrows(EmbeddedDeephavenException.class, () -> UpstreamSettings.from(none, "dhc-a"));
        assertTrue(error.getMessage().contains("DHC_UPSTREAM_DHC_A_URL is not set"), error.getMessage());
    }

    @Test
    void anAddressThatIsNotADeephavenAddressIsRejected() {
        for (String url : new String[] {"http://localhost:10000", "localhost:10000", "dh+plain://", "not an address"}) {
            EmbeddedDeephavenException error = assertThrows(EmbeddedDeephavenException.class,
                    () -> new UpstreamSettings("dhc-a", url, Optional.empty(), Duration.ofSeconds(1)), url);
            assertTrue(error.getMessage().contains("DHC_UPSTREAM_DHC_A_URL"), error.getMessage());
        }
    }

    @Test
    void scriptsAreToldWhichUpstreamsExist() {
        HostBindings host = new HostBindings(Map.of());

        EmbeddedDeephavenException error = assertThrows(EmbeddedDeephavenException.class, () -> host.remote("dhc-a"));
        assertTrue(error.getMessage().contains("no upstream called 'dhc-a'"), error.getMessage());
    }
}
