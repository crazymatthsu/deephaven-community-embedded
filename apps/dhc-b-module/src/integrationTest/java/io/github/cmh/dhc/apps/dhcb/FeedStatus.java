package io.github.cmh.dhc.apps.dhcb;

import static org.junit.jupiter.api.Assertions.fail;

import io.github.cmh.dhc.embed.EmbeddedDeephaven;
import io.github.cmh.dhc.testkit.TableAssertions;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Reads B's {@code feed_status}, the one-row table that says whether A is connected. */
final class FeedStatus {

    private FeedStatus() {}

    /** The row as it is now. */
    static Map<String, Object> row(EmbeddedDeephaven deephaven) {
        List<Map<String, Object>> rows = TableAssertions.rows(deephaven, deephaven.table("feed_status"));
        return rows.size() == 1 ? rows.get(0) : Map.of();
    }

    /** Waits until the row says this state and this many reconnects. */
    static Map<String, Object> await(EmbeddedDeephaven deephaven, String state, long reconnects, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        Map<String, Object> row = row(deephaven);
        while (!(state.equals(row.get("State")) && Long.valueOf(reconnects).equals(row.get("Reconnects")))) {
            if (System.nanoTime() > deadline) {
                fail("feed_status did not reach State=" + state + ", Reconnects=" + reconnects + " within " + timeout
                        + ". It says: " + row);
            }
            Thread.sleep(100);
            row = row(deephaven);
        }
        return row;
    }
}
