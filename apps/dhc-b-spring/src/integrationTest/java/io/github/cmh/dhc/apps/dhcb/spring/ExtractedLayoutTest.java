package io.github.cmh.dhc.apps.dhcb.spring;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.cmh.dhc.apps.dhcb.InstanceA;
import io.github.cmh.dhc.testkit.ChildApp;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T5: the layout that is shipped. {@code extractBootJar} unpacks the fat jar into a thin jar,
 * {@code lib/} and {@code jvm.args}; this starts it the way its documentation says,
 * {@code java @jvm.args -jar dhc-b-spring.jar}, beside instance A, and runs the same checks as
 * the in-process test. Nothing of Deephaven runs in this test's own JVM.
 */
class ExtractedLayoutTest {

    @Test
    void theExtractedLayoutStartsAndPassesTheSameChecks(@TempDir Path workDir) throws InterruptedException {
        Path jar = PackagedHost.EXTRACTED.resolve("dhc-b-spring.jar");
        assertTrue(Files.isDirectory(PackagedHost.EXTRACTED.resolve("lib")), "no lib directory beside " + jar);

        InstanceA a = InstanceA.anonymous();
        int springPort = ChildApp.freePort();
        ChildApp b = ChildApp.executableJar(jar)
                .jvmArgsFile(PackagedHost.EXTRACTED.resolve("jvm.args"))
                .env("SERVER_PORT", String.valueOf(springPort))
                .env("DHC_HTTP_PORT", String.valueOf(ChildApp.freePort()))
                .env("DHC_AUTH_MODE", "anonymous")
                .env("DHC_WORK_DIR", workDir.toString())
                .env("DHC_UPSTREAM_DHC_A_URL", a.url())
                .start();
        try {
            b.awaitOutput("Started DhcBSpringApp", InstanceA.PATIENCE);

            new HostChecks("http://localhost:" + springPort).all();

            // Asked to stop, as Ctrl-C or a container runtime would: it ends by itself, in order.
            // (Windows has no such request; there the process is ended and this part says nothing.)
            if (ChildApp.canAskToStop()) {
                long asked = System.nanoTime();
                assertTrue(b.stop(), "the application did not end when asked to. Its output:\n" + tail(b.output()));
                long seconds = (System.nanoTime() - asked) / 1_000_000_000;
                assertTrue(seconds < 30, "stopping took " + seconds + " s");
                String output = b.output();
                int web = output.indexOf("Graceful shutdown complete");
                int deephaven = output.indexOf("Stopping embedded Deephaven 'dhc-b'");
                assertTrue(web >= 0 && deephaven > web,
                        "expected the web server to finish its requests, then Deephaven to stop. Its output:\n"
                                + tail(output));
            }
        } finally {
            b.close();
            a.close();
        }
    }

    private static String tail(String output) {
        return output.length() <= 4_000 ? output : output.substring(output.length() - 4_000);
    }
}
