package io.github.cmh.dhc.apps.dhcb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deephaven.engine.table.Table;
import io.deephaven.engine.table.impl.BaseTable;
import io.github.cmh.dhc.testkit.EmbeddedDeephavenExtension;
import io.github.cmh.dhc.testkit.TableAssertions;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * T3: instance A is ended and started again while B is subscribed, twice: once as a crash, once
 * as an orderly stop. B does not recover by itself; it reconnects and rebuilds its tables, and
 * this test holds it to exactly what the documentation of {@code RemoteTables} says.
 */
class UpstreamRestartTest {

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
    void bReconnectsAndRebuildsItsTablesWhenAComesBack() throws InterruptedException {
        Table before = deephaven.table("orders_a");
        TableAssertions.awaitRows(deephaven.get(), before, InstanceA.expectedOrders(), InstanceA.PATIENCE);
        FeedStatus.await(deephaven.get(), "CONNECTED", 0, InstanceA.PATIENCE);

        // A crashes.
        a.kill();
        Map<String, Object> down = FeedStatus.await(deephaven.get(), "DISCONNECTED", 0, InstanceA.PATIENCE);
        assertEquals(0, down.get("Subscriptions"));
        // The table B had is failed for good, and still holds its last rows.
        assertTrue(((BaseTable<?>) before).isFailed(), "the table subscribed before the crash is not marked failed");
        assertEquals(InstanceA.expectedOrders().size(), TableAssertions.size(deephaven.get(), before));

        // A comes back, empty, and feeds the same executions again.
        a.start();
        Map<String, Object> up = FeedStatus.await(deephaven.get(), "CONNECTED", 1, InstanceA.PATIENCE);
        assertEquals(2, up.get("Subscriptions"));
        // The names now refer to new tables, built on the new subscription.
        Table after = deephaven.table("orders_a");
        assertNotSame(before, after);
        TableAssertions.awaitRows(deephaven.get(), after, InstanceA.expectedOrders(), InstanceA.PATIENCE);
        TableAssertions.awaitRows(deephaven.get(), deephaven.table("cum_by_symbol"), InstanceA.expectedCumBySymbol(),
                InstanceA.PATIENCE);
        assertTrue(((BaseTable<?>) before).isFailed(), "the old table came back to life, which the docs say it does not");

        // A is stopped in an orderly way and started again: the same again.
        a.stop();
        FeedStatus.await(deephaven.get(), "DISCONNECTED", 1, InstanceA.PATIENCE);
        a.start();
        FeedStatus.await(deephaven.get(), "CONNECTED", 2, InstanceA.PATIENCE);
        TableAssertions.awaitRows(deephaven.get(), deephaven.table("orders_a"), InstanceA.expectedOrders(),
                InstanceA.PATIENCE);
        TableAssertions.awaitRows(deephaven.get(), deephaven.table("cum_by_symbol"), InstanceA.expectedCumBySymbol(),
                InstanceA.PATIENCE);
    }
}
