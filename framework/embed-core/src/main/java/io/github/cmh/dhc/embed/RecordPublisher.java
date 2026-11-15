package io.github.cmh.dhc.embed;

import io.deephaven.engine.table.ColumnDefinition;
import io.deephaven.engine.table.Table;
import io.deephaven.engine.table.TableDefinition;
import io.deephaven.engine.table.impl.util.ColumnHolder;
import io.deephaven.engine.util.TableTools;
import io.deephaven.stream.TablePublisher;
import io.deephaven.util.QueryConstants;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Turns the host's records into rows of a table.
 *
 * <pre>{@code
 * RecordPublisher<ExecEvent> execs = RecordPublisher.<ExecEvent>builder("exec_events", ExecTables.EXEC_EVENTS)
 *         .column("ExecID", ExecEvent::execId)
 *         .column("LastQty", ExecEvent::lastQty)
 *         ...
 *         .build();
 * execs.offer(event);                  // from any thread of the host, at any time
 * Table table = execs.blinkTable();    // from a script, or inside engine().call(...)
 * }</pre>
 *
 * <p>{@link #offer} never blocks and never touches the engine: it puts the record in a queue. At
 * the start of each update cycle the engine asks for what has been queued, and all of it becomes
 * rows of that cycle. So a burst of records costs one table update, and the host's thread never
 * waits for the update graph.
 *
 * <p>The table is a blink table: it shows the rows of the current cycle only. Deephaven's
 * aggregations over a blink table remember what they have seen, so {@code lastBy} gives the
 * latest row per key and a ring table gives recent history.
 *
 * @param <T> the record type
 */
public final class RecordPublisher<T> {

    private static final List<Class<?>> SUPPORTED_TYPES = List.of(String.class, long.class, int.class, double.class,
            Instant.class);

    private final String name;
    private final TableDefinition definition;
    private final List<Function<? super T, ?>> extractors;
    private final Queue<T> queue = new ConcurrentLinkedQueue<>();
    private final AtomicLong offered = new AtomicLong();
    private final AtomicLong published = new AtomicLong();

    // Both are kept: the publisher gives the table up when nothing else refers to it.
    private TablePublisher publisher;
    private Table table;

    private RecordPublisher(String name, TableDefinition definition, List<Function<? super T, ?>> extractors) {
        this.name = name;
        this.definition = definition;
        this.extractors = extractors;
    }

    /**
     * @param name what Deephaven calls the stream in its logs
     * @param definition the table's columns; every one needs a {@link Builder#column value}
     */
    public static <T> Builder<T> builder(String name, TableDefinition definition) {
        return new Builder<>(name, definition);
    }

    /** Queues a record for the next update cycle. Any thread; never blocks. */
    public void offer(T record) {
        queue.add(record);
        offered.incrementAndGet();
    }

    /**
     * The table. The first call creates it and needs the engine: call it from an application
     * script or inside {@link Engine#call}. Records offered before that are kept and published.
     */
    public synchronized Table blinkTable() {
        if (table == null) {
            publisher = TablePublisher.of(name, definition, this::flush, null);
            table = publisher.table();
        }
        return table;
    }

    public TableDefinition definition() {
        return definition;
    }

    /** Records offered so far. */
    public long offered() {
        return offered.get();
    }

    /** Records that have become rows so far. */
    public long published() {
        return published.get();
    }

    /** Called by the engine at the start of every update cycle, on its own thread. */
    private void flush(TablePublisher target) {
        List<T> batch = new ArrayList<>();
        for (T record = queue.poll(); record != null; record = queue.poll()) {
            batch.add(record);
        }
        if (batch.isEmpty()) {
            return;
        }
        List<ColumnDefinition<?>> columns = definition.getColumns();
        ColumnHolder<?>[] holders = new ColumnHolder<?>[columns.size()];
        for (int c = 0; c < holders.length; c++) {
            holders[c] = holder(columns.get(c), extractors.get(c), batch);
        }
        target.add(TableTools.newTable(definition, holders));
        published.addAndGet(batch.size());
    }

    private ColumnHolder<?> holder(ColumnDefinition<?> column, Function<? super T, ?> extractor, List<T> batch) {
        String columnName = column.getName();
        Class<?> type = column.getDataType();
        int size = batch.size();
        try {
            if (type == String.class) {
                String[] values = new String[size];
                for (int i = 0; i < size; i++) {
                    Object value = extractor.apply(batch.get(i));
                    values[i] = value == null ? null : value.toString();
                }
                return TableTools.stringCol(columnName, values);
            }
            if (type == long.class) {
                long[] values = new long[size];
                for (int i = 0; i < size; i++) {
                    Object value = extractor.apply(batch.get(i));
                    values[i] = value == null ? QueryConstants.NULL_LONG : ((Number) value).longValue();
                }
                return TableTools.longCol(columnName, values);
            }
            if (type == int.class) {
                int[] values = new int[size];
                for (int i = 0; i < size; i++) {
                    Object value = extractor.apply(batch.get(i));
                    values[i] = value == null ? QueryConstants.NULL_INT : ((Number) value).intValue();
                }
                return TableTools.intCol(columnName, values);
            }
            if (type == double.class) {
                double[] values = new double[size];
                for (int i = 0; i < size; i++) {
                    Object value = extractor.apply(batch.get(i));
                    values[i] = value == null ? QueryConstants.NULL_DOUBLE : ((Number) value).doubleValue();
                }
                return TableTools.doubleCol(columnName, values);
            }
            Instant[] values = new Instant[size];
            for (int i = 0; i < size; i++) {
                values[i] = (Instant) extractor.apply(batch.get(i));
            }
            return TableTools.instantCol(columnName, values);
        } catch (ClassCastException e) {
            throw new EmbeddedDeephavenException("Column " + columnName + " of " + name + " is a "
                    + type.getSimpleName() + ", but its value function returned something else: " + e.getMessage(), e);
        }
    }

    public static final class Builder<T> {
        private final String name;
        private final TableDefinition definition;
        private final Map<String, Function<? super T, ?>> values = new LinkedHashMap<>();

        private Builder(String name, TableDefinition definition) {
            this.name = name;
            this.definition = definition;
            for (ColumnDefinition<?> column : definition.getColumns()) {
                if (!SUPPORTED_TYPES.contains(column.getDataType())) {
                    throw new EmbeddedDeephavenException("Column " + column.getName() + " of " + name + " is a "
                            + column.getDataType().getName() + ". RecordPublisher handles String, long, int, double"
                            + " and Instant columns.");
                }
            }
        }

        /**
         * How a record gives the value of a column. A String column takes any object and shows
         * its {@code toString()}, so an enum can be passed as it is.
         */
        public Builder<T> column(String columnName, Function<? super T, ?> value) {
            if (!definition.getColumnNames().contains(columnName)) {
                throw new EmbeddedDeephavenException(name + " has no column " + columnName + ". Its columns are "
                        + definition.getColumnNames() + ".");
            }
            if (values.put(columnName, value) != null) {
                throw new EmbeddedDeephavenException("Column " + columnName + " of " + name + " was given twice.");
            }
            return this;
        }

        public RecordPublisher<T> build() {
            List<String> missing = new ArrayList<>(definition.getColumnNames());
            missing.removeAll(values.keySet());
            if (!missing.isEmpty()) {
                throw new EmbeddedDeephavenException("Column(s) " + missing + " of " + name + " have no value."
                        + " Give each one with column(name, record -> value).");
            }
            List<Function<? super T, ?>> inColumnOrder = new ArrayList<>();
            for (String columnName : definition.getColumnNames()) {
                inColumnOrder.add(values.get(columnName));
            }
            return new RecordPublisher<>(name, definition, inColumnOrder);
        }
    }
}
