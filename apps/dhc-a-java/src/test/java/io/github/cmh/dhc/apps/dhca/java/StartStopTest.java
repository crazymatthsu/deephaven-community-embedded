package io.github.cmh.dhc.apps.dhca.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deephaven.engine.table.Table;
import io.github.cmh.dhc.embed.EmbedSettings;
import io.github.cmh.dhc.embed.EmbeddedDeephaven;
import io.github.cmh.dhc.embed.EmbeddedDeephavenException;
import io.github.cmh.dhc.embed.SideEffectPolicy;
import io.github.cmh.dhc.testkit.LiveThreads;
import io.github.cmh.dhc.testkit.TableAssertions;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T2: start, read {@code hello}, stop, and what is left afterwards. One test, because Deephaven
 * starts once per JVM (F10).
 */
class StartStopTest {

    /**
     * Daemon threads that outlive stop() because nothing offers a way to end them. Each is one
     * idle thread owned by a process-wide singleton, not by the stopped server.
     */
    private static final List<String> KNOWN_LEFTOVERS = List.of(
            "CleanupReferenceProcessor", // Deephaven's reference cleaner, a static singleton
            "JNA Cleaner", // the JNA library that Deephaven's directory lookup loads
            "process reaper"); // the JDK's own, ends by itself after a minute

    private static boolean isKnownLeftover(Thread thread) {
        return thread.isDaemon() && KNOWN_LEFTOVERS.stream().anyMatch(thread.getName()::startsWith);
    }

    @Test
    void startReadStopLeavesTheProcessAsItWas(@TempDir Path workDir) throws Exception {
        PrintStream out = System.out;
        PrintStream err = System.err;
        Thread.UncaughtExceptionHandler handler = Thread.getDefaultUncaughtExceptionHandler();
        LiveThreads before = LiveThreads.snapshot();

        EmbeddedDeephaven deephaven = EmbeddedDeephaven.builder()
                .settings(EmbedSettings.builder("dhc-a")
                        .httpPort(0)
                        .authMode(EmbedSettings.AuthMode.ANONYMOUS)
                        .appModules("hello")
                        .workDir(workDir)
                        .build())
                .sideEffects(SideEffectPolicy.hostKeepsControl())
                .bind("greeting", "hello from the test")
                .build();
        deephaven.start();

        // F5: with hostKeepsControl, Deephaven has changed none of these.
        assertSame(out, System.out, "System.out was replaced");
        assertSame(err, System.err, "System.err was replaced");
        assertSame(handler, Thread.getDefaultUncaughtExceptionHandler(), "the default uncaught-exception handler changed");

        // F10: the instance keeps its files in its own directory.
        assertTrue(Files.isRegularFile(workDir.resolve("app.d/hello.app")), "the module was not materialised");
        assertTrue(Files.isDirectory(workDir.resolve("cache")), "no cache directory of its own");

        Table hello = deephaven.table("hello");
        TableAssertions.awaitSize(deephaven, hello, 1, Duration.ofSeconds(30));
        assertEquals("hello from the test", TableAssertions.<String>column(deephaven, hello, "Message").get(0));

        deephaven.stop();

        assertFalse(deephaven.isRunning());
        List<Thread> left = before.startedSinceAndStillAlive(Duration.ofSeconds(5));
        List<Thread> keepingTheJvmAlive = left.stream().filter(thread -> !thread.isDaemon()).toList();
        assertEquals(List.of(), keepingTheJvmAlive,
                "threads that would keep the JVM alive after stop():" + LiveThreads.describe(keepingTheJvmAlive));
        List<Thread> unexpected = left.stream().filter(thread -> !isKnownLeftover(thread)).toList();
        assertEquals(List.of(), unexpected, "threads still running after stop():" + LiveThreads.describe(unexpected));

        // F10: one per JVM, and no restart.
        EmbeddedDeephavenException again = assertThrows(EmbeddedDeephavenException.class, deephaven::start);
        assertTrue(again.getMessage().contains("cannot be restarted"), again.getMessage());
    }
}
