package io.github.cmh.dhc.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** T1: the oracle against a replay of the same events through the cache. No engine involved. */
class ExecOracleTest {

    private static final GeneratorConfig CONFIG =
            GeneratorConfig.of(42, 12, Instant.parse("2026-01-05T14:30:00Z"), 5.0);

    @Test
    void theOracleAgreesWithAReplayThroughTheCache() {
        for (int events : new int[] {0, 1, 17, 400, 5_000}) {
            MockExecGenerator generator = new MockExecGenerator(CONFIG);
            OrderCache cache = new OrderCache();
            Map<String, ExecEvent> lastExec = new HashMap<>();
            for (int i = 0; i < events; i++) {
                ExecEvent event = generator.next();
                cache.apply(event);
                lastExec.put(event.orderId(), event);
            }

            ExecOracle oracle = ExecOracle.after(CONFIG, events);

            assertEquals(events, oracle.events());
            assertEquals(lastExec, oracle.lastExecByOrder(), "latest execution per order after " + events);
            Map<String, OrderState> cached = new HashMap<>();
            cache.snapshot().forEach(state -> cached.put(state.orderId(), state));
            assertEquals(cached, oracle.stateByOrder(), "order states after " + events);
            assertEquals(cache.size(), oracle.stateByOrder().size());
        }
    }

    @Test
    void ordersAreListedInTheOrderTheyFirstAppeared() {
        MockExecGenerator generator = new MockExecGenerator(CONFIG);
        List<String> firstAppearance = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            String orderId = generator.next().orderId();
            if (!firstAppearance.contains(orderId)) {
                firstAppearance.add(orderId);
            }
        }

        ExecOracle oracle = ExecOracle.after(CONFIG, 400);

        assertEquals(firstAppearance, List.copyOf(oracle.stateByOrder().keySet()));
        assertEquals(firstAppearance, List.copyOf(oracle.lastExecByOrder().keySet()));
    }

    @Test
    void anOrdersStateAddsUpFromItsFills() {
        ExecOracle oracle = ExecOracle.after(CONFIG, 400);

        assertTrue(oracle.stateByOrder().size() > 20, "orders: " + oracle.stateByOrder().size());
        oracle.stateByOrder().forEach((orderId, state) -> {
            assertEquals(state.orderQty(), state.cumQty() + state.leavesQty(), orderId);
            assertEquals(state.notional() / state.cumQty(), state.avgPx(), orderId);
            assertEquals(state.cumQty(), oracle.lastExecByOrder().get(orderId).cumQty(), orderId);
            assertEquals(state.seq(), oracle.lastExecByOrder().get(orderId).seq(), orderId);
        });
    }
}
