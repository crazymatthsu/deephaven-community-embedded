package io.github.cmh.dhc.apps.dhcb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.cmh.dhc.testkit.EmbeddedDeephavenExtension;
import io.github.cmh.dhc.testkit.TableAssertions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * T3: instance A runs as a process of its own with an event limit, role B runs here and
 * subscribes to it. B's tables then have one right answer, and the oracle knows it.
 */
class SubscribeToATest {

    // Declared before the extension: A has to be up when B starts and subscribes.
    static final InstanceA a = InstanceA.anonymous();

    @RegisterExtension
    static final EmbeddedDeephavenExtension deephaven = EmbeddedDeephavenExtension.forApp("dhc-b")
            .modules(RoleB.MODULE)
            .configure(builder -> builder.upstream(a.upstream(Optional.empty())))
            .build();

    @AfterAll
    static void stopA() {
        a.close();
    }

    @Test
    void ordersAEqualsTheOracleRowForRow() {
        TableAssertions.awaitRows(deephaven.get(), deephaven.table("orders_a"), InstanceA.expectedOrders(),
                InstanceA.PATIENCE);
    }

    @Test
    void cumBySymbolEqualsTheOracle() {
        TableAssertions.assertColumns(deephaven.table("cum_by_symbol"), "Symbol", "CumQty");
        TableAssertions.awaitRows(deephaven.get(), deephaven.table("cum_by_symbol"), InstanceA.expectedCumBySymbol(),
                InstanceA.PATIENCE);
    }

    @Test
    void execsAHasEveryExecutionInOrder() {
        TableAssertions.awaitRows(deephaven.get(), deephaven.table("execs_a"), InstanceA.expectedExecs(),
                InstanceA.PATIENCE);
    }

    @Test
    void openOrdersAreTheOrdersWithQuantityLeft() {
        List<Map<String, Object>> expected = InstanceA.expectedOrders().stream()
                .filter(row -> (long) row.get("LeavesQty") > 0)
                .toList();

        TableAssertions.awaitRows(deephaven.get(), deephaven.table("open_orders"), expected, InstanceA.PATIENCE);
    }

    @Test
    void execRateCountsEveryExecutionOnce() {
        // 600 executions at 2,000 a second from 14:30:00: all in one minute.
        TableAssertions.awaitRows(deephaven.get(), deephaven.table("exec_rate"),
                List.of(Map.of("Bin", java.time.Instant.parse("2026-01-05T14:30:00Z"), "Execs", 600L)),
                InstanceA.PATIENCE);
    }

    @Test
    void feedStatusSaysConnected() throws InterruptedException {
        Map<String, Object> status = FeedStatus.await(deephaven.get(), "CONNECTED", 0, InstanceA.PATIENCE);

        assertEquals("dhc-a", status.get("Upstream"));
        assertEquals(a.url(), status.get("Url"));
        assertEquals(2, status.get("Subscriptions"));
        assertEquals(null, status.get("LastError"));
    }
}
