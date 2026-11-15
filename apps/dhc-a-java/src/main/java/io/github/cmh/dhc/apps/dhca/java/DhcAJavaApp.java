package io.github.cmh.dhc.apps.dhca.java;

import io.github.cmh.dhc.apps.dhca.RoleA;
import io.github.cmh.dhc.apps.dhca.RoleASettings;
import io.github.cmh.dhc.embed.EmbedSettings;
import io.github.cmh.dhc.embed.EmbeddedDeephaven;
import io.github.cmh.dhc.embed.EmbeddedDeephavenException;
import java.util.Map;

/**
 * Host: a plain Java {@code main} that runs Deephaven inside its own JVM, with role A in it.
 *
 * <p>Everything about how Deephaven is started is in {@code embed-core}, and everything about
 * what the instance does is in the role module. A host only says which application it is.
 */
public final class DhcAJavaApp {

    static final String APP_ID = "dhc-a";

    /** What makes this application this application; the environment and -D override it. */
    static final Map<String, String> DEFAULTS = Map.of(
            EmbedSettings.APP_MODULES, RoleA.MODULE,
            EmbedSettings.HTTP_PORT, "10000");

    private DhcAJavaApp() {}

    public static void main(String[] args) {
        try {
            EmbedSettings settings = EmbedSettings.load(APP_ID, DEFAULTS);
            try (RoleA role = RoleA.create(RoleASettings.from(settings))) {
                EmbeddedDeephaven deephaven = role.bind(EmbeddedDeephaven.builder()
                        .settings(settings)
                        .hostClass(DhcAJavaApp.class))
                        .build();
                deephaven.start();
                role.start();
                // A plain Java host exists to run Deephaven: stay until it stops (Ctrl-C, or a kill).
                deephaven.awaitStopped();
            }
        } catch (EmbeddedDeephavenException e) {
            System.err.println("dhc-a-java cannot start: " + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
