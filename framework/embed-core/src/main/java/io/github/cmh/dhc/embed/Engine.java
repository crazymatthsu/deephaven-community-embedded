package io.github.cmh.dhc.embed;

import io.deephaven.engine.context.ExecutionContext;
import io.deephaven.engine.primitive.iterator.CloseableIterator;
import io.deephaven.engine.table.Table;
import io.deephaven.engine.updategraph.UpdateGraph;
import io.deephaven.util.SafeCloseable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The one safe way for host code to work with tables (finding F4).
 *
 * <p>A table operation needs two things a host thread does not have: Deephaven's execution
 * context, and the update graph's lock so the table does not change underneath it. Without them
 * the engine refuses with "May not initiate serial table operations". {@link #run} and
 * {@link #call} supply both, so host code never touches the lock.
 *
 * <p>Do only table work inside: read, filter, snapshot. Code that waits for something else in
 * there (another lock, a remote call) holds up every update of every table for as long.
 */
public final class Engine {

    private final ExecutionContext executionContext;
    private final UpdateGraph updateGraph;

    Engine(ExecutionContext executionContext, UpdateGraph updateGraph) {
        this.executionContext = executionContext;
        this.updateGraph = updateGraph;
    }

    /** Runs table operations. Tables do not change while it runs. */
    public void run(Runnable action) {
        call(() -> {
            action.run();
            return null;
        });
    }

    /** Runs table operations and returns their result. Tables do not change while it runs. */
    @SuppressWarnings("try") // the execution context only has to be open; it is not used by name
    public <T> T call(Supplier<T> action) {
        try (SafeCloseable ignored = executionContext.open()) {
            return updateGraph.sharedLock().computeLocked(action::get);
        }
    }

    /**
     * Every row of a table, top to bottom, as column name to value, all read at one moment. For
     * tables a host can afford to copy: a lookup result, a summary, a status row.
     */
    public List<Map<String, Object>> rows(Table table) {
        return call(() -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String name : table.getDefinition().getColumnNames()) {
                int row = 0;
                try (CloseableIterator<Object> iterator = table.columnIterator(name)) {
                    while (iterator.hasNext()) {
                        if (rows.size() <= row) {
                            rows.add(new LinkedHashMap<>());
                        }
                        rows.get(row++).put(name, iterator.next());
                    }
                }
            }
            return rows;
        });
    }
}
