package io.github.cmh.dhc.orders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The latest state of every order, in memory. It stands in for "the host application's own
 * cache": the kind of structure a business application already has, and which the embedded
 * Deephaven is there to show. It knows nothing about Deephaven.
 *
 * <p>Any thread may read. Executions are applied by one thread at a time; listeners are told of
 * each change on that thread, in the order the changes happened.
 */
public final class OrderCache {

    private final ConcurrentHashMap<String, OrderState> orders = new ConcurrentHashMap<>();
    private final List<Consumer<OrderState>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong updates = new AtomicLong();

    /** Applies an execution to its order and returns the order's new state. */
    public synchronized OrderState apply(ExecEvent event) {
        OrderState state = orders.compute(event.orderId(), (id, previous) -> OrderState.after(previous, event));
        updates.incrementAndGet();
        for (Consumer<OrderState> listener : listeners) {
            listener.accept(state);
        }
        return state;
    }

    /** Is told the new state of an order after every change. */
    public void addListener(Consumer<OrderState> listener) {
        listeners.add(listener);
    }

    public Optional<OrderState> get(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    /** The states at one moment, in no particular order. */
    public List<OrderState> snapshot() {
        return List.copyOf(orders.values());
    }

    /** Number of orders. */
    public int size() {
        return orders.size();
    }

    /** Number of executions applied since the cache was created. */
    public long updates() {
        return updates.get();
    }

    /** Time of the latest fill of the order that has gone longest without one; empty when there is no order. */
    public Optional<Instant> oldestTransactTime() {
        return orders.values().stream().map(OrderState::transactTime).min(Instant::compareTo);
    }
}
