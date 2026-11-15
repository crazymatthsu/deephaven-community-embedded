package io.github.cmh.dhc.orders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What every table has to contain after a given number of executions of a given feed. Tests
 * compare the engine's tables, and the {@link OrderCache}, with it.
 *
 * <p>It works the answer out its own way: it collects all executions of an order and then adds
 * them up, where the cache and the engine keep a running state. It shares the event sequence
 * with them (that is the input) and nothing else.
 */
public final class ExecOracle {

    private final long events;
    private final Map<String, ExecEvent> lastExecByOrder;
    private final Map<String, OrderState> stateByOrder;

    private ExecOracle(long events, Map<String, ExecEvent> lastExecByOrder, Map<String, OrderState> stateByOrder) {
        this.events = events;
        this.lastExecByOrder = Collections.unmodifiableMap(lastExecByOrder);
        this.stateByOrder = Collections.unmodifiableMap(stateByOrder);
    }

    /** The expected state once the first {@code events} executions of the feed have been processed. */
    public static ExecOracle after(GeneratorConfig config, long events) {
        MockExecGenerator generator = new MockExecGenerator(config);
        Map<String, List<ExecEvent>> byOrder = new LinkedHashMap<>();
        for (long i = 0; i < events; i++) {
            ExecEvent event = generator.next();
            byOrder.computeIfAbsent(event.orderId(), id -> new ArrayList<>()).add(event);
        }

        Map<String, ExecEvent> lastExec = new LinkedHashMap<>();
        Map<String, OrderState> states = new LinkedHashMap<>();
        byOrder.forEach((orderId, fills) -> {
            ExecEvent last = fills.get(fills.size() - 1);
            long filled = 0;
            double notional = 0.0;
            for (ExecEvent fill : fills) {
                filled += fill.lastQty();
                notional += fill.lastPx() * fill.lastQty();
            }
            lastExec.put(orderId, last);
            states.put(orderId, new OrderState(
                    orderId,
                    last.symbol(),
                    last.side(),
                    filled + last.leavesQty(),
                    filled,
                    last.leavesQty(),
                    notional / filled,
                    notional,
                    last.leavesQty() == 0 ? OrdStatus.FILLED : OrdStatus.PARTIALLY_FILLED,
                    fills.size(),
                    last.transactTime(),
                    last.seq()));
        });
        return new ExecOracle(events, lastExec, states);
    }

    /** Number of executions this is the answer for. */
    public long events() {
        return events;
    }

    /** The latest execution of every order, in the order the orders first appeared: the {@code orders} table. */
    public Map<String, ExecEvent> lastExecByOrder() {
        return lastExecByOrder;
    }

    /** The state of every order, in the order the orders first appeared: the {@code order_cache} table. */
    public Map<String, OrderState> stateByOrder() {
        return stateByOrder;
    }
}
