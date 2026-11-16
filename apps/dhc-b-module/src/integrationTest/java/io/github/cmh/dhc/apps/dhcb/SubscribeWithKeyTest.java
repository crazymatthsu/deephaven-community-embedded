package io.github.cmh.dhc.apps.dhcb;

import io.github.cmh.dhc.testkit.EmbeddedDeephavenExtension;
import io.github.cmh.dhc.testkit.TableAssertions;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** T3: the same as {@link SubscribeToATest}, with A asking for a pre-shared key and B presenting it. */
class SubscribeWithKeyTest {

    static final InstanceA a = InstanceA.withKey();

    @RegisterExtension
    static final EmbeddedDeephavenExtension deephaven = EmbeddedDeephavenExtension.forApp("dhc-b")
            .modules(RoleB.MODULE)
            .configure(builder -> builder.upstream(a.upstream(Optional.of(InstanceA.KEY))))
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
        TableAssertions.awaitRows(deephaven.get(), deephaven.table("cum_by_symbol"), InstanceA.expectedCumBySymbol(),
                InstanceA.PATIENCE);
    }

    @Test
    void feedStatusSaysConnected() throws InterruptedException {
        FeedStatus.await(deephaven.get(), "CONNECTED", 0, InstanceA.PATIENCE);
    }
}
