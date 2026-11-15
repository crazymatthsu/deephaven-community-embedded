package io.github.cmh.dhc.testkit;

import io.deephaven.engine.primitive.iterator.CloseableIterator;
import io.deephaven.engine.table.Table;
import io.github.cmh.dhc.embed.EmbeddedDeephaven;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;

/** Assertions on engine tables. Every read goes through {@code engine()}, as host code must (F4). */
public final class TableAssertions {

    private TableAssertions() {}

    /** Fails unless the table has exactly these columns, in this order. */
    public static void assertColumns(Table table, String... expected) {
        Assertions.assertEquals(List.of(expected), table.getDefinition().getColumnNames(), "columns of the table");
    }

    /** Waits until the table has at least this many rows; fails when the time is up. */
    public static void awaitSize(EmbeddedDeephaven deephaven, Table table, long atLeast, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        long size = size(deephaven, table);
        while (size < atLeast) {
            if (System.nanoTime() > deadline) {
                Assertions.fail("The table still has " + size + " row(s) after " + timeout + "; expected at least "
                        + atLeast + ".");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Assertions.fail("Interrupted while waiting for the table to reach " + atLeast + " row(s).");
            }
            size = size(deephaven, table);
        }
    }

    public static long size(EmbeddedDeephaven deephaven, Table table) {
        return deephaven.engine().call(table::size);
    }

    /** The values of one column, top to bottom, as they are at one moment. */
    public static <T> List<T> column(EmbeddedDeephaven deephaven, Table table, String name) {
        return deephaven.engine().call(() -> {
            List<T> values = new ArrayList<>();
            try (CloseableIterator<T> iterator = table.columnIterator(name)) {
                iterator.forEachRemaining(values::add);
            }
            return values;
        });
    }
}
