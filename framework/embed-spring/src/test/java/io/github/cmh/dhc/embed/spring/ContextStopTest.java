package io.github.cmh.dhc.embed.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.cmh.dhc.embed.EmbeddedDeephaven;
import io.github.cmh.dhc.embed.spring.host.TestHost;
import io.github.cmh.dhc.testkit.LiveThreads;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * T4: closing the Spring context stops Deephaven and leaves the process as it was. A class of its
 * own, because Deephaven starts once per JVM (F10).
 */
class ContextStopTest {

    /** The same three idle daemon threads a plain Java host is left with; see design section 4.1. */
    private static final List<String> KNOWN_LEFTOVERS = List.of("CleanupReferenceProcessor", "JNA Cleaner",
            "process reaper");

    private static boolean isKnownLeftover(Thread thread) {
        return thread.isDaemon() && KNOWN_LEFTOVERS.stream().anyMatch(thread.getName()::startsWith);
    }

    @Test
    void closingTheContextStopsDeephavenAndLeavesNoThreads(@TempDir Path workDir) throws Exception {
        PrintStream out = System.out;
        PrintStream err = System.err;
        Thread.UncaughtExceptionHandler handler = Thread.getDefaultUncaughtExceptionHandler();
        LiveThreads before = LiveThreads.snapshot();

        SpringApplication application = new SpringApplicationBuilder(TestHost.class)
                .properties("dhc.app.id=spring-test", "dhc.app.modules=spring-test", "dhc.http.port=0",
                        "dhc.auth.mode=anonymous", "server.port=0", "dhc.work.dir=" + workDir)
                // The test closes the context itself; no hook should do it a second time at JVM exit.
                .registerShutdownHook(false)
                .build();
        ConfigurableApplicationContext context = application.run();

        EmbeddedDeephaven deephaven = context.getBean(EmbeddedDeephaven.class);
        assertTrue(deephaven.isRunning());
        int deephavenPort = deephaven.httpPort();
        assertEquals(200, Http.get("http://localhost:" + deephavenPort + "/ide/").statusCode());
        assertSame(out, System.out, "System.out was replaced");
        assertSame(err, System.err, "System.err was replaced");
        assertSame(handler, Thread.getDefaultUncaughtExceptionHandler(), "the default uncaught-exception handler changed");

        context.close();

        assertFalse(deephaven.isRunning(), "Deephaven is still running after the context was closed");
        List<Thread> left = before.startedSinceAndStillAlive(Duration.ofSeconds(10));
        List<Thread> keepingTheJvmAlive = left.stream().filter(thread -> !thread.isDaemon()).toList();
        assertEquals(List.of(), keepingTheJvmAlive,
                "threads that would keep the JVM alive:" + LiveThreads.describe(keepingTheJvmAlive));
        List<Thread> unexpected = left.stream().filter(thread -> !isKnownLeftover(thread)).toList();
        assertEquals(List.of(), unexpected, "threads still running:" + LiveThreads.describe(unexpected));
    }
}
