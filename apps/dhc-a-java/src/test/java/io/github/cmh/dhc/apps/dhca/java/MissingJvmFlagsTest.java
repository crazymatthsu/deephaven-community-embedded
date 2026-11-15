package io.github.cmh.dhc.apps.dhca.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F3: the application, started the way a hand-written {@code java} command would start it, without
 * the mandatory flags. It has to stop at once and name the flags.
 */
class MissingJvmFlagsTest {

    @Test
    void startingWithoutTheFlagsFailsAndNamesThem(@TempDir Path workDir) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder command = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                DhcAJavaApp.class.getName());
        command.environment().put("DHC_HTTP_PORT", "0");
        command.environment().put("DHC_WORK_DIR", workDir.toString());
        command.redirectErrorStream(true);

        Process process = command.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the application did not exit");

        assertEquals(1, process.exitValue(), output);
        assertTrue(output.contains("dhc-a-java cannot start"), output);
        for (String flag : List.of(
                "--add-exports=java.management/sun.management=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-opens=java.base/java.nio=ALL-UNNAMED")) {
            assertTrue(output.contains(flag), "the message does not name " + flag + ":\n" + output);
        }
    }
}
