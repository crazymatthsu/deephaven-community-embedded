package io.github.cmh.dhc.embed;

import io.deephaven.engine.context.ExecutionContext;
import io.deephaven.engine.updategraph.UpdateGraph;
import io.deephaven.util.SafeCloseable;
import java.util.function.Supplier;

/**
 * The one safe way for host code to work with tables (finding F4).
 *
 * <p>A table operation needs two things a host thread does not have: Deephaven's execution
 * context, and the update graph's lock so the table does not change underneath it. Without them
 * the engine refuses with "May not initiate serial table operations". {@link #run} and
 * {@link #call} supply both, so host code never touches the lock.
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
}
