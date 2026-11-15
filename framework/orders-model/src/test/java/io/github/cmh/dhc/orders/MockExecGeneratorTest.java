package io.github.cmh.dhc.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** T1: the generator. No engine involved. */
class MockExecGeneratorTest {

    private static final Instant START = Instant.parse("2026-01-05T14:30:00Z");

    private static GeneratorConfig config(long seed) {
        return GeneratorConfig.of(seed, 12, START, 5.0);
    }

    private static List<ExecEvent> events(GeneratorConfig config, int count) {
        MockExecGenerator generator = new MockExecGenerator(config);
        List<ExecEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(generator.next());
        }
        return events;
    }

    @Test
    void theSameConfigurationGivesTheSameEvents() {
        assertEquals(events(config(42), 2_000), events(config(42), 2_000));
    }

    @Test
    void anotherSeedGivesOtherEvents() {
        assertNotEquals(events(config(42), 200), events(config(43), 200));
    }

    @Test
    void sequenceNumbersCountFromOneAndTimesFollowFromThem() {
        List<ExecEvent> events = events(config(42), 500);

        for (int i = 0; i < events.size(); i++) {
            ExecEvent event = events.get(i);
            assertEquals(i + 1, event.seq());
            assertEquals(String.format("E%09d", i + 1), event.execId());
            // 5 events a second: 200 ms apart, from the configured start, never from the clock.
            assertEquals(START.plus(Duration.ofMillis(200L * i)), event.transactTime());
        }
    }

    @Test
    void everyFillIsConsistentWithTheOrderItBelongsTo() {
        Map<String, ExecEvent> previousByOrder = new HashMap<>();
        Map<String, Long> orderQty = new HashMap<>();
        Set<String> filled = new HashSet<>();

        for (ExecEvent event : events(config(7), 5_000)) {
            assertTrue(event.lastQty() > 0 && event.lastQty() % 100 == 0, "a fill is whole lots: " + event);
            assertTrue(event.lastPx() > 0, event.toString());
            assertEquals(Math.round(event.lastPx() * 100) / 100.0, event.lastPx(), "a price is whole cents: " + event);
            assertTrue(event.leavesQty() >= 0, event.toString());
            assertTrue(!filled.contains(event.orderId()), "a fill after the order was filled: " + event);

            ExecEvent previous = previousByOrder.put(event.orderId(), event);
            long before = previous == null ? 0 : previous.cumQty();
            assertEquals(before + event.lastQty(), event.cumQty(), event.toString());
            Long size = orderQty.putIfAbsent(event.orderId(), event.cumQty() + event.leavesQty());
            if (size != null) {
                assertEquals(size, event.cumQty() + event.leavesQty(), "the order changed size: " + event);
                assertEquals(previous.symbol(), event.symbol());
                assertEquals(previous.side(), event.side());
            }
            assertEquals(event.leavesQty() == 0 ? OrdStatus.FILLED : OrdStatus.PARTIALLY_FILLED, event.ordStatus());
            if (event.leavesQty() == 0) {
                filled.add(event.orderId());
            }
        }
        assertTrue(filled.size() > 100, "orders filled: " + filled.size());
    }

    @Test
    void theNumberOfOpenOrdersStaysAsConfigured() {
        Set<String> open = new HashSet<>();
        int most = 0;
        for (ExecEvent event : events(config(42), 3_000)) {
            open.add(event.orderId());
            most = Math.max(most, open.size());
            if (event.leavesQty() == 0) {
                open.remove(event.orderId());
            }
        }
        // Only orders that have had a fill are visible here, so the count never exceeds the setting.
        assertTrue(most <= 12, "open at once: " + most);
        assertTrue(most >= 10, "open at once: " + most);
    }
}
