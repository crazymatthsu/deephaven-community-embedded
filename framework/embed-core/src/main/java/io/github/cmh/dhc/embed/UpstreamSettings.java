package io.github.cmh.dhc.embed;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Where another Deephaven instance is and how to log in to it, for an instance that subscribes
 * to its tables.
 *
 * @param id the upstream's application id, for example {@code dhc-a}; scripts ask for it by this name
 * @param url {@code dh+plain://host:port} for an unencrypted connection, {@code dh://host:port} for TLS
 * @param preSharedKey the upstream's pre-shared key; empty when the upstream allows anonymous access
 * @param connectTimeout how long to keep trying when the upstream does not answer
 */
public record UpstreamSettings(String id, String url, Optional<String> preSharedKey, Duration connectTimeout) {

    /** How long to keep trying, for every upstream. */
    public static final String CONNECT_TIMEOUT_SECONDS = "DHC_UPSTREAM_CONNECT_TIMEOUT_SECONDS";

    public UpstreamSettings {
        if (id == null || !id.matches("[a-z][a-z0-9-]*")) {
            throw new EmbeddedDeephavenException("An upstream id is lower-case letters, digits and '-', for example"
                    + " dhc-a. Got: " + id);
        }
        URI parsed;
        try {
            parsed = URI.create(url);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new EmbeddedDeephavenException(urlKey(id) + " is not an address: " + url);
        }
        boolean knownScheme = "dh+plain".equals(parsed.getScheme()) || "dh".equals(parsed.getScheme());
        if (!knownScheme || parsed.getHost() == null) {
            throw new EmbeddedDeephavenException(urlKey(id) + " must look like dh+plain://host:port (or dh://host:port"
                    + " for TLS), but it is: " + url);
        }
    }

    /** {@code DHC_UPSTREAM_DHC_A_URL} for the upstream {@code dhc-a}. */
    public static String urlKey(String id) {
        return "DHC_UPSTREAM_" + keyPart(id) + "_URL";
    }

    /** {@code DHC_UPSTREAM_DHC_A_PSK} for the upstream {@code dhc-a}. A secret: environment only. */
    public static String pskKey(String id) {
        return "DHC_UPSTREAM_" + keyPart(id) + "_PSK";
    }

    private static String keyPart(String id) {
        return String.valueOf(id).toUpperCase(Locale.ROOT).replace('-', '_');
    }

    /** The upstream as the settings describe it. */
    public static UpstreamSettings from(EmbedSettings settings, String id) {
        String url = settings.value(urlKey(id)).orElseThrow(() -> new EmbeddedDeephavenException(
                urlKey(id) + " is not set. It says where the upstream instance " + id + " is, for example"
                        + " dh+plain://localhost:10000."));
        return new UpstreamSettings(id, url, settings.value(pskKey(id)),
                Duration.ofSeconds(settings.longValue(CONNECT_TIMEOUT_SECONDS, 60)));
    }

    /** Never shows the key. */
    @Override
    public String toString() {
        return "UpstreamSettings{id=" + id + ", url=" + url + ", preSharedKey="
                + (preSharedKey.isPresent() ? "supplied" : "none") + ", connectTimeout=" + connectTimeout + "}";
    }
}
