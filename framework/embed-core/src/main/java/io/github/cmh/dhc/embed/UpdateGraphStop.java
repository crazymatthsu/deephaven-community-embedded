package io.github.cmh.dhc.embed;

import io.deephaven.engine.updategraph.impl.PeriodicUpdateGraph;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ends Deephaven's update graph without the deadlock that {@code PeriodicUpdateGraph.stop()} has
 * on its own (finding F14).
 *
 * <p>Deephaven's {@code stop()} ends the threads that process a cycle's notifications and then
 * waits for the cycle in flight to finish. A cycle that is still waiting for those threads never
 * finishes, so {@code stop()} never returns, and neither does whatever called it: a Spring context
 * being closed, a JVM handling Ctrl-C. An instance with live subscriptions has such work in
 * almost every cycle.
 *
 * <p>The cure is to call {@code stop()} while holding the update graph's exclusive lock. A cycle
 * holds that lock from start to end, so with the lock in hand none is in flight. One gap is left:
 * a cycle that began to wait for the lock while this thread held it would run afterwards, with
 * no threads to do its work. So before stopping, this class looks whether the refresh thread is
 * queued for the lock, and if it is, lets that cycle run first and tries again.
 */
final class UpdateGraphStop {

    private static final Logger log = LoggerFactory.getLogger(UpdateGraphStop.class);

    private static final int ATTEMPTS = 100;
    private static final long REFRESH_THREAD_END_MILLIS = 5_000;

    private UpdateGraphStop() {}

    static void stop(PeriodicUpdateGraph graph) {
        Thread refreshThread = refreshThread(graph);
        boolean[] stopped = new boolean[1];
        for (int attempt = 1; !stopped[0]; attempt++) {
            boolean lastAttempt = attempt == ATTEMPTS;
            graph.exclusiveLock().doLocked(() -> {
                if (!lastAttempt && isQueuedForALock(refreshThread)) {
                    return; // a cycle is about to start: give it the lock, with its threads still there
                }
                graph.stop();
                stopped[0] = true;
            });
        }
        if (refreshThread == null) {
            return;
        }
        try {
            refreshThread.join(REFRESH_THREAD_END_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (refreshThread.isAlive()) {
            log.warn("Deephaven's refresh thread {} did not end within {} ms of the update graph being stopped."
                    + " It is a daemon thread and does not keep the JVM alive.", refreshThread.getName(),
                    REFRESH_THREAD_END_MILLIS);
        }
    }

    /** The thread that runs the graph's cycles; Deephaven names it after the graph. Null if not found. */
    private static Thread refreshThread(PeriodicUpdateGraph graph) {
        String name = "PeriodicUpdateGraph." + graph.getName() + ".refreshThread";
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (name.equals(thread.getName())) {
                return thread;
            }
        }
        return null;
    }

    /**
     * Whether a thread is parked waiting for a read-write lock, which for the refresh thread can
     * only be the update graph's: between cycles it waits on a monitor, and that is not a lock.
     */
    private static boolean isQueuedForALock(Thread thread) {
        if (thread == null) {
            return false;
        }
        Object blocker = LockSupport.getBlocker(thread);
        return blocker != null && blocker.getClass().getName().contains("ReentrantReadWriteLock");
    }
}
