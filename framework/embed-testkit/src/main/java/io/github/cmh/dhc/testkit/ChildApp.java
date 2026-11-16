package io.github.cmh.dhc.testkit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Another application of this repository, run as a process of its own from its installed
 * distribution ({@code ./gradlew :apps:<app>:installDist}). Deephaven is one per JVM (F10), so a
 * test with two instances needs two JVMs: one is the test's, the other is this.
 *
 * <pre>{@code
 * try (ChildApp a = ChildApp.distribution(installDir, "io.github.cmh.dhc.apps.dhca.java.DhcAJavaApp")
 *         .env("DHC_HTTP_PORT", String.valueOf(port))
 *         .start()) {
 *     a.awaitOutput("is up:", Duration.ofSeconds(60));
 *     ...
 * }
 * }</pre>
 *
 * <p>It starts {@code java} on the distribution's application jar, with the JVM flags Deephaven
 * needs, and not the start script. The script is checked by {@code scripts/check-start.sh};
 * starting the JVM directly gives a process this class can stop the same way on every system.
 */
public final class ChildApp implements AutoCloseable {

    /** The flags of finding F3. build-logic applies them to the builds; a hand-started JVM needs them spelled out. */
    private static final List<String> JVM_FLAGS = List.of(
            "--add-exports=java.management/sun.management=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens=java.base/java.nio=ALL-UNNAMED");

    private final String name;
    private final Process process;
    private final StringBuffer output = new StringBuffer();
    private final Thread reader;

    private ChildApp(String name, Process process) {
        this.name = name;
        this.process = process;
        this.reader = new Thread(() -> {
            try (BufferedReader lines =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                    output.append(line).append('\n');
                }
            } catch (IOException e) {
                output.append("[output of ").append(name).append(" ended: ").append(e).append("]\n");
            }
        }, "child-app-output-" + name);
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * @param installDir the distribution, for example {@code apps/dhc-a-java/build/install/dhc-a-java}
     * @param mainClass the application's main class
     */
    public static Builder distribution(Path installDir, String mainClass) {
        return new Builder(installDir, mainClass);
    }

    /** A port nothing listens on right now. Another process could take it before it is used; tests accept that. */
    public static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Waits until the application has printed this text; fails with its output when it exits or the time is up. */
    public void awaitOutput(String text, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (output.indexOf(text) < 0) {
            if (!process.isAlive()) {
                throw new AssertionError(name + " exited with status " + process.exitValue() + " before printing '"
                        + text + "'. Its output:\n" + output);
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError(name + " did not print '" + text + "' within " + timeout
                        + ". Its output:\n" + output);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + name);
            }
        }
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /** Everything the application has printed so far. */
    public String output() {
        return output.toString();
    }

    /** Asks the application to stop, as Ctrl-C or a container runtime would, and waits for it. */
    public void stop() {
        end(false);
    }

    /** Ends the application at once, as a crash or power loss would: nothing in it gets to clean up. */
    public void kill() {
        end(true);
    }

    private void end(boolean forcibly) {
        if (forcibly) {
            process.destroyForcibly();
        } else {
            process.destroy();
        }
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(30, TimeUnit.SECONDS);
            }
            reader.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stops the application if it is still running. */
    @Override
    public void close() {
        if (process.isAlive()) {
            stop();
        }
    }

    public static final class Builder {
        private final Path installDir;
        private final String mainClass;
        private final Map<String, String> environment = new LinkedHashMap<>();

        private Builder(Path installDir, String mainClass) {
            this.installDir = installDir;
            this.mainClass = mainClass;
        }

        /** A setting for the application, as it would get it in production: an environment variable. */
        public Builder env(String key, String value) {
            environment.put(key, value);
            return this;
        }

        public ChildApp start() {
            String name = installDir.getFileName().toString();
            Path jar = applicationJar(name);
            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.addAll(JVM_FLAGS);
            // One jar: its manifest lists the others, which is also what keeps this command short on Windows.
            command.add("-cp");
            command.add(jar.toString());
            command.add(mainClass);
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            // The child gets what is asked for here and nothing of this build's own settings.
            builder.environment().keySet().removeIf(key -> key.startsWith("DHC_"));
            builder.environment().putAll(environment);
            try {
                return new ChildApp(name, builder.start());
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot start " + name + " with " + command, e);
            }
        }

        private Path applicationJar(String name) {
            Path lib = installDir.resolve("lib");
            if (!Files.isDirectory(lib)) {
                throw new IllegalStateException(installDir + " is not an installed distribution (no lib directory)."
                        + " Build it first: ./gradlew :apps:" + name + ":installDist");
            }
            try (Stream<Path> jars = Files.list(lib)) {
                return jars.filter(jar -> jar.getFileName().toString().matches(name + "(-[0-9].*)?\\.jar"))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("No " + name + " jar in " + lib));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
