package io.github.cmh.dhc.apps.dhcb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.cmh.dhc.orders.ExecEvent;
import io.github.cmh.dhc.testkit.Probe;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * The probe against a key-protected instance A in another process: what a script or an operator
 * would see from outside. No Deephaven runs in this JVM.
 */
class ProbeTest {

    static final InstanceA a = InstanceA.withKey();

    @AfterAll
    static void stopA() {
        a.close();
    }

    private static long expectedCumQty() {
        return InstanceA.ORACLE.lastExecByOrder().values().stream().mapToLong(ExecEvent::cumQty).sum();
    }

    @Test
    void itReadsRowCountAndSumsOfATable() throws InterruptedException {
        // A feeds 600 executions in a fraction of a second; give its tables a moment to have them all.
        Probe.Result result = Probe.read(a.url(), Optional.of(InstanceA.KEY), "orders", List.of("CumQty"));
        long deadline = System.nanoTime() + InstanceA.PATIENCE.toNanos();
        while (result.sums().get("CumQty").longValue() != expectedCumQty() && System.nanoTime() < deadline) {
            Thread.sleep(200);
            result = Probe.read(a.url(), Optional.of(InstanceA.KEY), "orders", List.of("CumQty"));
        }

        assertEquals(InstanceA.ORACLE.lastExecByOrder().size(), result.rows());
        assertEquals(expectedCumQty(), result.sums().get("CumQty"));
    }

    @Test
    void theCommandPrintsWhatItRead() throws InterruptedException {
        itReadsRowCountAndSumsOfATable();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int status = Probe.run(new String[] {a.url(), "orders", "--sum", "CumQty"}, Optional.of(InstanceA.KEY),
                new PrintStream(out, true, StandardCharsets.UTF_8), System.err);

        assertEquals(0, status);
        assertEquals("rows=" + InstanceA.ORACLE.lastExecByOrder().size() + "\nsum.CumQty=" + expectedCumQty() + "\n",
                out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void aWrongKeyAndAnUnknownTableAreReportedInOneLine() {
        for (String[] attempt : new String[][] {{"orders", "not-the-key"}, {"no_such_table", InstanceA.KEY}}) {
            ByteArrayOutputStream err = new ByteArrayOutputStream();

            int status = Probe.run(new String[] {a.url(), attempt[0]}, Optional.of(attempt[1]), System.out,
                    new PrintStream(err, true, StandardCharsets.UTF_8));

            String message = err.toString(StandardCharsets.UTF_8);
            assertEquals(1, status, message);
            assertTrue(message.startsWith("Probe: cannot read table '" + attempt[0] + "' at " + a.url()), message);
            assertEquals(1, message.strip().lines().count(), message);
            assertFalse(message.contains("not-the-key"), message);
        }
    }
}
