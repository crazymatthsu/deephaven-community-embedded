package io.github.cmh.dhc.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** T1: the host's cache. No engine involved. */
class OrderCacheTest {

    private static final Instant T0 = Instant.parse("2026-01-05T14:30:00Z");

    private static ExecEvent fill(String orderId, long seq, long lastQty, double lastPx, long cumQty, long leavesQty) {
        return new ExecEvent("E" + seq, orderId, "AAPL", Side.BUY, lastQty, lastPx, cumQty, leavesQty,
                leavesQty == 0 ? OrdStatus.FILLED : OrdStatus.PARTIALLY_FILLED, T0.plusSeconds(seq), seq);
    }

    @Test
    void anEmptyCacheHasNothingToSay() {
        OrderCache cache = new OrderCache();

        assertEquals(0, cache.size());
        assertEquals(0, cache.updates());
        assertTrue(cache.get("O1").isEmpty());
        assertTrue(cache.oldestTransactTime().isEmpty());
    }

    @Test
    void fillsAccumulateIntoTheOrdersState() {
        OrderCache cache = new OrderCache();

        cache.apply(fill("O1", 1, 100, 10.0, 100, 200));
        OrderState state = cache.apply(fill("O1", 2, 200, 13.0, 300, 0));

        assertEquals(new OrderState("O1", "AAPL", Side.BUY, 300, 300, 0, 12.0, 3600.0, OrdStatus.FILLED, 2,
                T0.plusSeconds(2), 2), state);
        assertEquals(state, cache.get("O1").orElseThrow());
        assertEquals(1, cache.size());
        assertEquals(2, cache.updates());
    }

    @Test
    void listenersHearEveryChangeInOrder() {
        OrderCache cache = new OrderCache();
        List<OrderState> heard = new ArrayList<>();
        cache.addListener(heard::add);

        cache.apply(fill("O1", 1, 100, 10.0, 100, 200));
        cache.apply(fill("O2", 2, 100, 20.0, 100, 0));
        cache.apply(fill("O1", 3, 200, 10.0, 300, 0));

        assertEquals(List.of(1L, 2L, 3L), heard.stream().map(OrderState::seq).toList());
        assertEquals(List.of("O1", "O2", "O1"), heard.stream().map(OrderState::orderId).toList());
    }

    @Test
    void theOldestEntryIsTheOrderLongestWithoutAFill() {
        OrderCache cache = new OrderCache();

        cache.apply(fill("O1", 1, 100, 10.0, 100, 200));
        cache.apply(fill("O2", 2, 100, 20.0, 100, 100));
        assertEquals(T0.plusSeconds(1), cache.oldestTransactTime().orElseThrow());

        cache.apply(fill("O1", 3, 100, 10.0, 200, 100));
        assertEquals(T0.plusSeconds(2), cache.oldestTransactTime().orElseThrow());
    }
}
