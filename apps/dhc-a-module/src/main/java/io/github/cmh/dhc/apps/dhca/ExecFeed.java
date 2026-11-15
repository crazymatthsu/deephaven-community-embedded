package io.github.cmh.dhc.apps.dhca;

import io.deephaven.engine.table.Table;
import io.github.cmh.dhc.embed.RecordPublisher;
import io.github.cmh.dhc.orders.ExecEvent;
import io.github.cmh.dhc.orders.ExecTables;
import io.github.cmh.dhc.orders.MockExecGenerator;
import io.github.cmh.dhc.orders.OrderCache;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * The host's source of executions: a thread that takes them from the mock generator at the
 * configured rate, applies each to the {@link OrderCache}, and offers it to the engine. Scripts
 * read it as {@code host.execFeed}.
 *
 * <p>The cache comes first, as it would in a real application: the business state changes, and
 * the table is a view of what happened.
 */
public final class ExecFeed implements AutoCloseable {

    private final RoleASettings settings;
    private final OrderCache cache;
    private final MockExecGenerator generator;
    private final RecordPublisher<ExecEvent> publisher;
    private final AtomicLong fed = new AtomicLong();
    private final CountDownLatch limitReached = new CountDownLatch(1);
    private volatile boolean stopped;
    private Thread thread;

    ExecFeed(RoleASettings settings, OrderCache cache) {
        this.settings = settings;
        this.cache = cache;
        this.generator = new MockExecGenerator(settings.generatorConfig());
        this.publisher = RecordPublisher.<ExecEvent>builder("exec_events", ExecTables.EXEC_EVENTS)
                .column("ExecID", ExecEvent::execId)
                .column("OrderID", ExecEvent::orderId)
                .column("Symbol", ExecEvent::symbol)
                .column("Side", ExecEvent::side)
                .column("LastQty", ExecEvent::lastQty)
                .column("LastPx", ExecEvent::lastPx)
                .column("CumQty", ExecEvent::cumQty)
                .column("LeavesQty", ExecEvent::leavesQty)
                .column("OrdStatus", ExecEvent::ordStatus)
                .column("TransactTime", ExecEvent::transactTime)
                .column("Seq", ExecEvent::seq)
                .build();
    }

    /** One row per execution, in the update cycle it arrived in. For scripts. */
    public Table blinkTable() {
        return publisher.blinkTable();
    }

    /** Starts feeding. Call it once Deephaven has started, so that the scripts have built on the table. */
    public synchronized void start() {
        if (thread != null) {
            return;
        }
        thread = new Thread(this::run, "dhc-a-exec-feed");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        long intervalNanos = Math.max(1, Math.round(1e9 / settings.eventsPerSecond()));
        long started = System.nanoTime();
        while (!stopped && (settings.eventLimit() == 0 || fed.get() < settings.eventLimit())) {
            // Paced against the start, so a slow moment is caught up and the rate holds on average.
            long due = started + fed.get() * intervalNanos;
            long wait = due - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(wait);
                continue;
            }
            ExecEvent event = generator.next();
            cache.apply(event);
            publisher.offer(event);
            fed.incrementAndGet();
        }
        if (!stopped) {
            limitReached.countDown();
        }
    }

    /** Executions fed so far. */
    public long fed() {
        return fed.get();
    }

    /** Executions that have become table rows so far. */
    public long published() {
        return publisher.published();
    }

    /**
     * Waits until the feed has stopped at its event limit and every execution is a table row.
     *
     * @return false if that did not happen in time, or the feed has no limit
     */
    public boolean awaitLimit(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        if (settings.eventLimit() == 0 || !limitReached.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
            return false;
        }
        while (publisher.published() < settings.eventLimit()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    /** Stops feeding. */
    @Override
    public synchronized void close() {
        stopped = true;
        if (thread != null) {
            LockSupport.unpark(thread);
        }
    }
}
