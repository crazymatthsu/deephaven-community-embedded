package io.github.cmh.dhc.orders;

import io.deephaven.engine.table.ColumnDefinition;
import io.deephaven.engine.table.TableDefinition;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The columns of the demo's tables, defined once: role A publishes with these definitions, and
 * tests turn expected records into rows with the {@code row} methods.
 */
public final class ExecTables {

    private ExecTables() {}

    /** One row per execution: {@code exec_events}, {@code execs}, and {@code orders} (the latest per order). */
    public static final TableDefinition EXEC_EVENTS = TableDefinition.of(
            ColumnDefinition.ofString("ExecID"),
            ColumnDefinition.ofString("OrderID"),
            ColumnDefinition.ofString("Symbol"),
            ColumnDefinition.ofString("Side"),
            ColumnDefinition.ofLong("LastQty"),
            ColumnDefinition.ofDouble("LastPx"),
            ColumnDefinition.ofLong("CumQty"),
            ColumnDefinition.ofLong("LeavesQty"),
            ColumnDefinition.ofString("OrdStatus"),
            ColumnDefinition.ofTime("TransactTime"),
            ColumnDefinition.ofLong("Seq"));

    /** One row per order, as the host's cache has it: {@code order_cache}. */
    public static final TableDefinition ORDER_CACHE = TableDefinition.of(
            ColumnDefinition.ofString("OrderID"),
            ColumnDefinition.ofString("Symbol"),
            ColumnDefinition.ofString("Side"),
            ColumnDefinition.ofLong("OrderQty"),
            ColumnDefinition.ofLong("CumQty"),
            ColumnDefinition.ofLong("LeavesQty"),
            ColumnDefinition.ofDouble("AvgPx"),
            ColumnDefinition.ofDouble("Notional"),
            ColumnDefinition.ofString("OrdStatus"),
            ColumnDefinition.ofLong("ExecCount"),
            ColumnDefinition.ofTime("TransactTime"),
            ColumnDefinition.ofLong("Seq"));

    /** One row, replaced about once a second, with what the cache says about itself: {@code cache_stats}. */
    public static final TableDefinition CACHE_STATS = TableDefinition.of(
            ColumnDefinition.ofTime("Timestamp"),
            ColumnDefinition.ofLong("Size"),
            ColumnDefinition.ofLong("Updates"),
            ColumnDefinition.ofDouble("UpdatesPerSecond"),
            ColumnDefinition.ofTime("OldestTransactTime"));

    /** An execution as a row of {@link #EXEC_EVENTS}: column name to value, in column order. */
    public static Map<String, Object> row(ExecEvent event) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ExecID", event.execId());
        row.put("OrderID", event.orderId());
        row.put("Symbol", event.symbol());
        row.put("Side", event.side().name());
        row.put("LastQty", event.lastQty());
        row.put("LastPx", event.lastPx());
        row.put("CumQty", event.cumQty());
        row.put("LeavesQty", event.leavesQty());
        row.put("OrdStatus", event.ordStatus().name());
        row.put("TransactTime", event.transactTime());
        row.put("Seq", event.seq());
        return row;
    }

    /** An order's state as a row of {@link #ORDER_CACHE}: column name to value, in column order. */
    public static Map<String, Object> row(OrderState state) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("OrderID", state.orderId());
        row.put("Symbol", state.symbol());
        row.put("Side", state.side().name());
        row.put("OrderQty", state.orderQty());
        row.put("CumQty", state.cumQty());
        row.put("LeavesQty", state.leavesQty());
        row.put("AvgPx", state.avgPx());
        row.put("Notional", state.notional());
        row.put("OrdStatus", state.ordStatus().name());
        row.put("ExecCount", state.execCount());
        row.put("TransactTime", state.transactTime());
        row.put("Seq", state.seq());
        return row;
    }
}
