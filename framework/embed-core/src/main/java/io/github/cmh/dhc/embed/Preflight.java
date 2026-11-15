package io.github.cmh.dhc.embed;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks made before Deephaven is touched, for the mistakes that otherwise surface much later as
 * an error nobody can read: missing JVM flags (F3), a fat jar (F6), a taken port, a directory
 * that cannot be written.
 */
final class Preflight {

    /**
     * The flags of finding F3, for the message. They are applied by build-logic
     * ({@code DeephavenRules.mandatoryJvmFlags}); this class only checks their effect.
     */
    static final List<String> MANDATORY_JVM_FLAGS = List.of(
            "--add-exports=java.management/sun.management=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens=java.base/java.nio=ALL-UNNAMED");

    private Preflight() {}

    static void check(EmbedSettings settings) {
        checkJvmFlags(missingJvmFlags());
        checkNotNestedJar(codeLocation());
        checkWritable(settings.workDir());
        checkPortFree(settings.httpHost(), settings.httpPort());
    }

    /** The mandatory flags this JVM was started without, judged by their effect. */
    static List<String> missingJvmFlags() {
        Module here = Preflight.class.getModule();
        Module javaBase = Object.class.getModule();
        List<String> missing = new ArrayList<>();
        boolean management = ModuleLayer.boot().findModule("java.management")
                .map(module -> module.isExported("sun.management", here))
                .orElse(false);
        if (!management) {
            missing.add(MANDATORY_JVM_FLAGS.get(0));
        }
        if (!javaBase.isExported("jdk.internal.misc", here)) {
            missing.add(MANDATORY_JVM_FLAGS.get(1));
        }
        if (!javaBase.isOpen("java.nio", here)) {
            missing.add(MANDATORY_JVM_FLAGS.get(2));
        }
        return missing;
    }

    static void checkJvmFlags(List<String> missing) {
        if (missing.isEmpty()) {
            return;
        }
        throw new EmbeddedDeephavenException("This JVM was started without " + missing.size() + " of the "
                + MANDATORY_JVM_FLAGS.size() + " flags Deephaven needs:\n  " + String.join("\n  ", missing)
                + "\nThey cannot be set from inside the JVM. Start Java with all of:\n  "
                + String.join(" ", MANDATORY_JVM_FLAGS)
                + "\nThe Gradle run task and the installDist start script pass them already; a hand-written java"
                + " command, an IDE run configuration or a container entry point has to pass them too.");
    }

    /** Where this class was loaded from, as text; null when the JVM does not say. */
    private static String codeLocation() {
        CodeSource source = Preflight.class.getProtectionDomain().getCodeSource();
        return source == null || source.getLocation() == null ? null : source.getLocation().toString();
    }

    /**
     * Refuses to run from inside another jar. Deephaven compiles query formulas with javac at run
     * time, and javac cannot read classes in a jar within a jar.
     */
    static void checkNotNestedJar(String location) {
        if (location == null) {
            return;
        }
        // Spring Boot 3.2 and later: jar:nested:/app.jar/!BOOT-INF/lib/x.jar!/ ; before: jar:file:/app.jar!/BOOT-INF/...
        boolean nested = location.startsWith("nested:") || location.startsWith("jar:nested:")
                || location.contains("!BOOT-INF/") || location.contains("!/BOOT-INF/") || location.contains("!/WEB-INF/");
        if (nested) {
            throw new EmbeddedDeephavenException("Deephaven cannot run from inside a fat jar (" + location + ")."
                    + " It compiles query formulas with javac at run time, and javac cannot read classes in a jar"
                    + " within a jar, so the first formula would fail with 'Error Compiling Formula Expression'."
                    + " Run the extracted layout instead: java -Djarmode=tools -jar <app>.jar extract, then start"
                    + " the thin jar it writes. A plain Java host uses the installDist distribution.");
        }
    }

    static void checkWritable(Path workDir) {
        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            throw new EmbeddedDeephavenException("Cannot create the work directory " + workDir.toAbsolutePath()
                    + ". Point " + EmbedSettings.WORK_DIR + " at a directory this process may write to.", e);
        }
        if (!Files.isWritable(workDir)) {
            throw new EmbeddedDeephavenException("The work directory " + workDir.toAbsolutePath()
                    + " is not writable. Point " + EmbedSettings.WORK_DIR + " at a directory this process may write to.");
        }
    }

    static void checkPortFree(String host, int port) {
        if (port == 0) {
            return;
        }
        try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getByName(host))) {
            socket.setReuseAddress(true);
        } catch (IOException e) {
            throw new EmbeddedDeephavenException("Port " + port + " on " + host + " cannot be used: " + e.getMessage()
                    + ". Another process may be listening there, perhaps an earlier run of this application. Set "
                    + EmbedSettings.HTTP_PORT + " to a free port, or to 0 to have one picked.", e);
        }
    }
}
