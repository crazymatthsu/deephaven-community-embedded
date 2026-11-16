package io.github.cmh.dhc.apps.dhcb.spring;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.cmh.dhc.testkit.ChildApp;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T5: Spring Boot's fat jar is not a way to run this application (F6). Started from it, the
 * application refuses at once and says what to run instead, rather than starting and failing on
 * the first formula.
 */
class FatJarRefusedTest {

    @Test
    void theFatJarIsRefusedWithWhatToRunInstead(@TempDir Path workDir) {
        try (ChildApp b = ChildApp.executableJar(PackagedHost.FAT_JAR)
                .jvmArgsFile(PackagedHost.EXTRACTED.resolve("jvm.args"))
                .env("SERVER_PORT", "0")
                .env("DHC_HTTP_PORT", "0")
                .env("DHC_AUTH_MODE", "anonymous")
                .env("DHC_WORK_DIR", workDir.toString())
                .start()) {
            int status = b.awaitExit(Duration.ofSeconds(90));
            String output = b.output();

            assertNotEquals(0, status, "the fat jar ran. Its output:\n" + output);
            assertTrue(output.contains("APPLICATION FAILED TO START"), output);
            assertTrue(output.contains("Deephaven cannot run from inside a fat jar"), output);
            assertTrue(output.contains("java -Djarmode=tools -jar <app>.jar extract"), output);
            // The report is the message; the reader is not sent through a stack trace for it.
            assertTrue(output.indexOf("Deephaven cannot run from inside a fat jar")
                    > output.indexOf("APPLICATION FAILED TO START"), output);
        }
    }
}
