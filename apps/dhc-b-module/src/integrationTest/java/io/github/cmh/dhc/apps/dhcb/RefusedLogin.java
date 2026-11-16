package io.github.cmh.dhc.apps.dhcb;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.cmh.dhc.embed.EmbedSettings;
import io.github.cmh.dhc.embed.EmbeddedDeephaven;
import io.github.cmh.dhc.embed.EmbeddedDeephavenException;
import io.github.cmh.dhc.embed.SideEffectPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;

/** Starts B against an A that will refuse it, and returns the error B's start ends with. */
final class RefusedLogin {

    private RefusedLogin() {}

    static EmbeddedDeephavenException startB(InstanceA a, Optional<String> key) throws IOException {
        EmbeddedDeephaven b = EmbeddedDeephaven.builder()
                .settings(EmbedSettings.builder("dhc-b")
                        .httpPort(0)
                        .authMode(EmbedSettings.AuthMode.ANONYMOUS)
                        .appModules(RoleB.MODULE)
                        .workDir(Files.createTempDirectory("dhc-b-refused-"))
                        .build())
                .sideEffects(SideEffectPolicy.hostKeepsControl())
                .upstream(a.upstream(key))
                .build();
        try {
            return assertThrows(EmbeddedDeephavenException.class, b::start);
        } finally {
            b.stop();
        }
    }

    /** The whole chain of messages, which is what a person reading the log gets. */
    static String messages(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            text.append(cause.getMessage()).append('\n');
        }
        return text.toString();
    }
}
