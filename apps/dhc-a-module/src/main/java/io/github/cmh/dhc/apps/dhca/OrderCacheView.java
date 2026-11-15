package io.github.cmh.dhc.apps.dhca;

import io.deephaven.engine.table.Table;
import io.github.cmh.dhc.embed.RecordPublisher;
import io.github.cmh.dhc.orders.ExecTables;
import io.github.cmh.dhc.orders.OrderCache;
import io.github.cmh.dhc.orders.OrderState;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The host's {@link OrderCache}, shown as tables. Scripts read it as {@code host.orderCache}.
 *
 * <p>This is the point of the embedding: the cache is the application's own structure and knows
 * nothing about Deephaven. This class listens to it and publishes what it hears.
 */
public final class OrderCacheView implements AutoCloseable {

    /** What the cache says about itself at one moment. */
    private record Stats(Instant timestamp, long size, long updates, double updatesPerSecond, Instant oldest) {
    }

    private final OrderCache cache;
    private final RecordPublisher<OrderState> changes;
    private final RecordPublisher<Stats> stats;
    private ScheduledExecutorService sampler;
    private Table table;
    private Table statsTable;
    private long sampledUpdates;
    private long sampledAtNanos;

    OrderCacheView(OrderCache cache) {
        this.cache = cache;
        this.changes = RecordPublisher.<OrderState>builder("order_cache_changes", ExecTables.ORDER_CACHE)
                .column("OrderID", OrderState::orderId)
                .column("Symbol", OrderState::symbol)
                .column("Side", OrderState::side)
                .column("OrderQty", OrderState::orderQty)
                .column("CumQty", OrderState::cumQty)
                .column("LeavesQty", OrderState::leavesQty)
                .column("AvgPx", OrderState::avgPx)
                .column("Notional", OrderState::notional)
                .column("OrdStatus", OrderState::ordStatus)
                .column("ExecCount", OrderState::execCount)
                .column("TransactTime", OrderState::transactTime)
                .column("Seq", OrderState::seq)
                .build();
        this.stats = RecordPublisher.<Stats>builder("cache_stats", ExecTables.CACHE_STATS)
                .column("Timestamp", Stats::timestamp)
                .column("Size", Stats::size)
                .column("Updates", Stats::updates)
                .column("UpdatesPerSecond", Stats::updatesPerSecond)
                .column("OldestTransactTime", Stats::oldest)
                .build();
        cache.addListener(changes::offer);
    }

    /** One row per order in the cache, changing as the cache changes. For scripts. */
    public synchronized Table asTable() {
        if (table == null) {
            // The cache reports each change; the latest report per order is the cache's content.
            table = changes.blinkTable().lastBy("OrderID");
        }
        return table;
    }

    /** One row: the cache's size, its update count and rate, and its oldest entry. For scripts. */
    public synchronized Table statsTable() {
        if (statsTable == null) {
            statsTable = stats.blinkTable().lastBy();
        }
        return statsTable;
    }

    /** Starts reporting the cache's statistics, once now and then once a second. */
    synchronized void start() {
        if (sampler != null) {
            return;
        }
        sampledUpdates = cache.updates();
        sampledAtNanos = System.nanoTime();
        sampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dhc-a-cache-stats");
            thread.setDaemon(true);
            return thread;
        });
        sampler.scheduleAtFixedRate(this::sample, 0, 1, TimeUnit.SECONDS);
    }

    private void sample() {
        long updates = cache.updates();
        long now = System.nanoTime();
        double seconds = (now - sampledAtNanos) / 1e9;
        double rate = seconds > 0 ? (updates - sampledUpdates) / seconds : 0.0;
        sampledUpdates = updates;
        sampledAtNanos = now;
        stats.offer(new Stats(Instant.now(), cache.size(), updates, rate, cache.oldestTransactTime().orElse(null)));
    }

    @Override
    public synchronized void close() {
        if (sampler != null) {
            sampler.shutdownNow();
        }
    }
}
