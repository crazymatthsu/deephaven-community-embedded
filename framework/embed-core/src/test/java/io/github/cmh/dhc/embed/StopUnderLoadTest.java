package io.github.cmh.dhc.embed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deephaven.engine.table.Table;
import io.deephaven.engine.util.TableTools;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T2: {@code stop()} returns while tables are being updated (finding F14).
 *
 * <p>Deephaven's own way of stopping its update graph waits for ever when it is called during a
 * cycle that still has work queued. Here every cycle has such work, and has it for most of a
 * second: a slow formula, and a second table that can only be updated after it. Called at any
 * moment, {@code stop()} lands in the middle of a cycle. With {@code PeriodicUpdateGraph.stop()}
 * called directly this test does not finish.
 */
public class StopUnderLoadTest { // public: the formula below is compiled outside this package

    private static final AtomicLong CALLS = new AtomicLong();

    /** Called from a formula, once per new row. Slow on purpose. */
    public static long slow(long value) {
        CALLS.incrementAndGet();
        try {
            Thread.sleep(80);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return value;
    }

    @Test
    void stopReturnsInTheMiddleOfACycle(@TempDir Path workDir) throws Exception {
        EmbeddedDeephaven deephaven = EmbeddedDeephaven.builder()
                .settings(EmbedSettings.builder("stop-under-load")
                        .httpPort(0)
                        .authMode(EmbedSettings.AuthMode.ANONYMOUS)
                        .workDir(workDir)
                        .build())
                .sideEffects(SideEffectPolicy.hostKeepsControl())
                .build();
        deephaven.start();

        // Ten new rows a second, 80 ms each: the first update fills most of every cycle, and the
        // second one is queued behind it.
        Table[] keep = new Table[2];
        deephaven.engine().run(() -> {
            keep[0] = TableTools.timeTable("PT0.1S")
                    .update("Slow = " + StopUnderLoadTest.class.getName() + ".slow(ii)");
            keep[1] = keep[0].update("After = Slow + 1");
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (CALLS.get() < 25 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(CALLS.get() >= 25, "the slow formula is not being called; nothing is under load");

        Thread stopper = new Thread(deephaven::stop, "stop-under-load");
        stopper.setDaemon(true);
        stopper.start();
        stopper.join(Duration.ofSeconds(30).toMillis());

        assertFalse(stopper.isAlive(), "stop() had not returned after 30 seconds; it is stuck at:\n  "
                + String.join("\n  ", Arrays.stream(stopper.getStackTrace()).map(String::valueOf).toList()));
        assertFalse(deephaven.isRunning());
        assertTrue(keep[1] != null);
    }
}
