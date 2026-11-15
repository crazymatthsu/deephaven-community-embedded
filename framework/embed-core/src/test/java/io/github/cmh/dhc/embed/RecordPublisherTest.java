package io.github.cmh.dhc.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deephaven.engine.primitive.iterator.CloseableIterator;
import io.deephaven.engine.table.ColumnDefinition;
import io.deephaven.engine.table.Table;
import io.deephaven.engine.table.TableDefinition;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** T2: records offered by host threads become rows, each exactly once. Runs a real engine. */
class RecordPublisherTest {

    private record Tick(String name, Long count, Integer small, Double price, Instant time) {
    }

    private static final TableDefinition TICKS = TableDefinition.of(
            ColumnDefinition.ofString("Name"),
            ColumnDefinition.ofLong("Count"),
            ColumnDefinition.ofInt("Small"),
            ColumnDefinition.ofDouble("Price"),
            ColumnDefinition.ofTime("Time"));

    private static EmbeddedDeephaven deephaven;

    @BeforeAll
    static void start() throws Exception {
        deephaven = EmbeddedDeephaven.builder()
                .settings(EmbedSettings.builder("publisher-test")
                        .httpPort(0)
                        .authMode(EmbedSettings.AuthMode.ANONYMOUS)
                        .workDir(Files.createTempDirectory("dhc-publisher-test-"))
                        .build())
                .sideEffects(SideEffectPolicy.hostKeepsControl())
                .build();
        deephaven.start();
    }

    @AfterAll
    static void stop() {
        deephaven.stop();
    }

    private static RecordPublisher<Tick> publisher(String name) {
        return RecordPublisher.<Tick>builder(name, TICKS)
                .column("Name", Tick::name)
                .column("Count", Tick::count)
                .column("Small", Tick::small)
                .column("Price", Tick::price)
                .column("Time", Tick::time)
                .build();
    }

    @Test
    void recordsFromSeveralThreadsEachBecomeOneRow() throws Exception {
        RecordPublisher<Tick> ticks = publisher("ticks");
        // Aggregations over a blink table remember what they have seen: a running total.
        Table totals = deephaven.engine().call(
                () -> ticks.blinkTable().view("Count", "Small").updateView("Rows = 1L").sumBy());

        int threads = 4;
        int perThread = 5_000;
        List<Thread> producers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread producer = new Thread(() -> {
                for (int i = 1; i <= perThread; i++) {
                    ticks.offer(new Tick("t", (long) i, 1, 1.5, Instant.EPOCH));
                }
            });
            producers.add(producer);
            producer.start();
        }
        for (Thread producer : producers) {
            producer.join();
        }

        long expectedRows = (long) threads * perThread;
        long expectedCount = threads * ((long) perThread * (perThread + 1) / 2);
        await(() -> ticks.published() == expectedRows && Long.valueOf(expectedRows).equals(first(totals, "Rows")));

        assertEquals(expectedRows, ticks.offered());
        assertEquals(expectedRows, ticks.published());
        assertEquals(expectedRows, first(totals, "Rows"));
        assertEquals(expectedCount, first(totals, "Count"));
        assertEquals(expectedRows, first(totals, "Small"));
    }

    @Test
    void valuesAndNullsArriveAsGiven() throws Exception {
        RecordPublisher<Tick> ticks = publisher("ticks_values");
        Table last = deephaven.engine().call(() -> ticks.blinkTable().lastBy());
        Instant time = Instant.parse("2026-01-05T14:30:00.123456789Z");

        ticks.offer(new Tick("first", 7L, 3, 1.25, time));
        await(() -> "first".equals(first(last, "Name")));
        assertEquals(7L, first(last, "Count"));
        assertEquals(3, first(last, "Small"));
        assertEquals(1.25, first(last, "Price"));
        assertEquals(time, first(last, "Time"));

        ticks.offer(new Tick(null, null, null, null, null));
        await(() -> first(last, "Name") == null);
        // Deephaven's nulls: a reserved value for primitives, which the iterator gives back as null.
        assertNull(first(last, "Count"));
        assertNull(first(last, "Small"));
        assertNull(first(last, "Price"));
        assertNull(first(last, "Time"));
    }

    @Test
    void recordsOfferedBeforeTheTableExistsAreKept() throws Exception {
        RecordPublisher<Tick> ticks = publisher("ticks_early");
        for (long i = 1; i <= 10; i++) {
            ticks.offer(new Tick("early", i, 1, 1.0, Instant.EPOCH));
        }

        Table totals = deephaven.engine().call(() -> ticks.blinkTable().view("Count").sumBy());

        await(() -> Long.valueOf(55).equals(first(totals, "Count")));
        assertEquals(10, ticks.published());
    }

    @Test
    void everyColumnNeedsAValueAndOnlyKnownColumnsTakeOne() {
        EmbeddedDeephavenException missing = assertThrows(EmbeddedDeephavenException.class,
                () -> RecordPublisher.<Tick>builder("ticks", TICKS).column("Name", Tick::name).build());
        assertTrue(missing.getMessage().contains("[Count, Small, Price, Time]"), missing.getMessage());

        EmbeddedDeephavenException unknown = assertThrows(EmbeddedDeephavenException.class,
                () -> RecordPublisher.<Tick>builder("ticks", TICKS).column("Nmae", Tick::name));
        assertTrue(unknown.getMessage().contains("no column Nmae"), unknown.getMessage());

        EmbeddedDeephavenException twice = assertThrows(EmbeddedDeephavenException.class,
                () -> RecordPublisher.<Tick>builder("ticks", TICKS).column("Name", Tick::name).column("Name", Tick::name));
        assertTrue(twice.getMessage().contains("given twice"), twice.getMessage());
    }

    @Test
    void anUnsupportedColumnTypeIsRefusedWhenThePublisherIsDefined() {
        TableDefinition withBoolean = TableDefinition.of(ColumnDefinition.ofBoolean("Flag"));

        EmbeddedDeephavenException error = assertThrows(EmbeddedDeephavenException.class,
                () -> RecordPublisher.<Tick>builder("flags", withBoolean));
        assertTrue(error.getMessage().contains("Column Flag"), error.getMessage());
    }

    /** The value in the first row of a column, or null when the table is empty. */
    private static Object first(Table table, String column) {
        return deephaven.engine().call(() -> {
            try (CloseableIterator<Object> iterator = table.columnIterator(column)) {
                return iterator.hasNext() ? iterator.next() : null;
            }
        });
    }

    private static void await(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (!condition.get()) {
            assertTrue(System.nanoTime() < deadline, "the table did not reach the expected state in 30 seconds");
            Thread.sleep(20);
        }
    }
}
