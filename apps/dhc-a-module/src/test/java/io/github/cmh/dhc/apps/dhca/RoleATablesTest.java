package io.github.cmh.dhc.apps.dhca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deephaven.engine.table.Table;
import io.deephaven.engine.util.TableTools;
import io.github.cmh.dhc.embed.EmbeddedDeephavenException;
import io.github.cmh.dhc.orders.ExecEvent;
import io.github.cmh.dhc.orders.ExecOracle;
import io.github.cmh.dhc.orders.ExecTables;
import io.github.cmh.dhc.orders.MockExecGenerator;
import io.github.cmh.dhc.orders.OrderState;
import io.github.cmh.dhc.testkit.EmbeddedDeephavenExtension;
import io.github.cmh.dhc.testkit.TableAssertions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * T2: role A's tables, built by a real engine from a feed with a fixed seed and an event limit,
 * against the oracle. The limit is what makes this exact: once the feed has stopped, every table
 * has one right answer.
 */
class RoleATablesTest {

    private static final int EVENTS = 600;
    private static final int RING_CAPACITY = 250;
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    // 2,000 a second: the 600 executions arrive over many update cycles, several to a cycle.
    private static final RoleASettings SETTINGS =
            new RoleASettings(42, 2_000.0, 12, EVENTS, RING_CAPACITY, Instant.parse("2026-01-05T14:30:00Z"));
    private static final RoleA role = RoleA.create(SETTINGS);
    private static final ExecOracle oracle = ExecOracle.after(SETTINGS.generatorConfig(), EVENTS);

    @RegisterExtension
    static final EmbeddedDeephavenExtension deephaven =
            EmbeddedDeephavenExtension.forApp("dhc-a").modules(RoleA.MODULE).configure(role::bind).build();

    @BeforeAll
    static void feedAllEvents() throws InterruptedException {
        role.start();
        assertTrue(role.feed().awaitLimit(PATIENCE), "the feed did not reach its limit; fed " + role.feed().fed()
                + ", published " + role.feed().published());
    }

    @Test
    void ordersEqualsTheOracleRowForRow() {
        List<Map<String, Object>> expected = oracle.lastExecByOrder().values().stream().map(ExecTables::row).toList();

        // lastBy puts its key first; otherwise the columns are those of an execution.
        TableAssertions.assertColumns(deephaven.table("orders"), "OrderID", "ExecID", "Symbol", "Side", "LastQty",
                "LastPx", "CumQty", "LeavesQty", "OrdStatus", "TransactTime", "Seq");
        TableAssertions.awaitRows(deephaven.get(), deephaven.table("orders"), expected, PATIENCE);
    }

    @Test
    void orderCacheEqualsTheOracleRowForRow() {
        List<Map<String, Object>> expected = oracle.stateByOrder().values().stream().map(ExecTables::row).toList();

        TableAssertions.assertColumns(deephaven.table("order_cache"), ExecTables.ORDER_CACHE.getColumnNamesArray());
        TableAssertions.awaitRows(deephaven.get(), deephaven.table("order_cache"), expected, PATIENCE);
    }

    @Test
    void orderCacheHasTheJavaCachesContent() {
        // The table is a view of the host's cache, so it has to say what the cache says.
        List<Map<String, Object>> rows = TableAssertions.rows(deephaven.get(), awaitSettled("order_cache"));

        assertEquals(role.cache().size(), rows.size());
        for (Map<String, Object> row : rows) {
            OrderState cached = role.cache().get((String) row.get("OrderID")).orElseThrow();
            assertEquals(ExecTables.row(cached), row);
        }
    }

    @Test
    void execsKeepsTheMostRecentExecutionsUpToTheRingCapacity() {
        MockExecGenerator generator = new MockExecGenerator(SETTINGS.generatorConfig());
        List<ExecEvent> all = new ArrayList<>();
        for (int i = 0; i < EVENTS; i++) {
            all.add(generator.next());
        }
        List<Map<String, Object>> expected =
                all.subList(EVENTS - RING_CAPACITY, EVENTS).stream().map(ExecTables::row).toList();

        TableAssertions.awaitRows(deephaven.get(), deephaven.table("execs"), expected, PATIENCE);
    }

    @Test
    void execEventsShowsOnlyTheCurrentCycle() {
        // A blink table: with the feed stopped there is nothing in the current cycle.
        TableAssertions.assertColumns(deephaven.table("exec_events"), ExecTables.EXEC_EVENTS.getColumnNamesArray());
        TableAssertions.awaitRows(deephaven.get(), deephaven.table("exec_events"), List.of(), PATIENCE);
    }

    @Test
    void cacheStatsReportsTheJavaCachesSize() {
        Table stats = deephaven.table("cache_stats");
        TableAssertions.assertColumns(stats, ExecTables.CACHE_STATS.getColumnNamesArray());

        // Sampled once a second, so the row with the final numbers arrives within a second or two.
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        List<Map<String, Object>> rows = TableAssertions.rows(deephaven.get(), stats);
        while ((rows.size() != 1 || !Long.valueOf(EVENTS).equals(rows.get(0).get("Updates")))
                && System.nanoTime() < deadline) {
            rows = TableAssertions.rows(deephaven.get(), stats);
        }
        assertEquals(1, rows.size(), "cache_stats is one row");
        assertEquals((long) EVENTS, rows.get(0).get("Updates"));
        assertEquals((long) role.cache().size(), rows.get(0).get("Size"));
        assertEquals((long) oracle.stateByOrder().size(), rows.get(0).get("Size"));
        assertEquals(role.cache().oldestTransactTime().orElseThrow(), rows.get(0).get("OldestTransactTime"));
    }

    @Test
    void hostCodeCanBuildTablesThroughTheEngine() {
        // F4: without engine() this fails with "May not initiate serial table operations".
        long rows = deephaven.engine().call(() -> TableTools.emptyTable(5).update("X = ii * 2").size());

        assertEquals(5, rows);
    }

    @Test
    void anUnknownTableIsReportedWithTheNamesThatExist() {
        EmbeddedDeephavenException error = assertThrows(EmbeddedDeephavenException.class, () -> deephaven.table("nope"));

        assertTrue(error.getMessage().contains("There is no table 'nope'"), error.getMessage());
        assertTrue(error.getMessage().contains("order_cache"), error.getMessage());
    }

    private static Table awaitSettled(String name) {
        Table table = deephaven.table(name);
        TableAssertions.awaitSize(deephaven.get(), table, oracle.stateByOrder().size(), PATIENCE);
        return table;
    }
}
