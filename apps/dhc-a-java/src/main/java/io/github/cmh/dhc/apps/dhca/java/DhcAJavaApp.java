package io.github.cmh.dhc.apps.dhca.java;

import io.github.cmh.dhc.embed.EmbedSettings;
import io.github.cmh.dhc.embed.EmbeddedDeephaven;
import io.github.cmh.dhc.embed.EmbeddedDeephavenException;
import java.util.Map;

/**
 * Host: a plain Java {@code main} that runs Deephaven inside its own JVM.
 *
 * <p>Until phase P3 it runs the {@code hello} module, one ticking table. Everything about how
 * Deephaven is started is in {@code embed-core}; a host only says which application it is and
 * what it lends to the scripts.
 */
public final class DhcAJavaApp {

    static final String APP_ID = "dhc-a";

    /** What makes this application this application; the environment and -D override it. */
    static final Map<String, String> DEFAULTS = Map.of(
            EmbedSettings.APP_MODULES, "hello",
            EmbedSettings.HTTP_PORT, "10000");

    private DhcAJavaApp() {}

    public static void main(String[] args) {
        try {
            EmbeddedDeephaven deephaven = EmbeddedDeephaven.builder()
                    .settings(EmbedSettings.load(APP_ID, DEFAULTS))
                    .hostClass(DhcAJavaApp.class)
                    .bind("greeting", "hello from " + APP_ID)
                    .build();
            deephaven.start();
            // A plain Java host exists to run Deephaven: stay until it stops (Ctrl-C, or a kill).
            deephaven.awaitStopped();
        } catch (EmbeddedDeephavenException e) {
            System.err.println("dhc-a-java cannot start: " + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
