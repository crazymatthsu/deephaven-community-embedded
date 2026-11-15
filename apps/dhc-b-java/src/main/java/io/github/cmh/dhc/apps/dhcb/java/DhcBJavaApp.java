package io.github.cmh.dhc.apps.dhcb.java;

import io.github.cmh.dhc.apps.dhcb.RoleB;
import io.github.cmh.dhc.embed.EmbedSettings;
import io.github.cmh.dhc.embed.EmbeddedDeephaven;
import io.github.cmh.dhc.embed.EmbeddedDeephavenException;
import io.github.cmh.dhc.embed.UpstreamSettings;
import java.util.Map;

/**
 * Host: a plain Java {@code main} that runs Deephaven inside its own JVM, with role B in it.
 * Instance A has to be reachable; this instance waits for it at start.
 */
public final class DhcBJavaApp {

    static final String APP_ID = "dhc-b";

    /** What makes this application this application; the environment and -D override it. */
    static final Map<String, String> DEFAULTS = Map.of(
            EmbedSettings.APP_MODULES, RoleB.MODULE,
            EmbedSettings.HTTP_PORT, "10001",
            UpstreamSettings.urlKey(RoleB.UPSTREAM), "dh+plain://localhost:10000");

    private DhcBJavaApp() {}

    public static void main(String[] args) {
        try {
            EmbedSettings settings = EmbedSettings.load(APP_ID, DEFAULTS);
            EmbeddedDeephaven deephaven = RoleB.bind(EmbeddedDeephaven.builder()
                    .settings(settings)
                    .hostClass(DhcBJavaApp.class), settings)
                    .build();
            deephaven.start();
            // A plain Java host exists to run Deephaven: stay until it stops (Ctrl-C, or a kill).
            deephaven.awaitStopped();
        } catch (EmbeddedDeephavenException e) {
            System.err.println("dhc-b-java cannot start: " + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
