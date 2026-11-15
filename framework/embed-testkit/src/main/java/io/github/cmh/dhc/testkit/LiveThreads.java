package io.github.cmh.dhc.testkit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Which threads something started and left behind.
 *
 * <pre>{@code
 * LiveThreads before = LiveThreads.snapshot();
 * ... start and stop ...
 * List<Thread> left = before.startedSinceAndStillAlive(Duration.ofSeconds(20));
 * }</pre>
 */
public final class LiveThreads {

    private final Set<Thread> threads;

    private LiveThreads(Set<Thread> threads) {
        this.threads = threads;
    }

    public static LiveThreads snapshot() {
        return new LiveThreads(Set.copyOf(Thread.getAllStackTraces().keySet()));
    }

    /**
     * The threads that did not exist at the snapshot and are still alive, after giving them up to
     * {@code grace} to end. Sorted by name.
     */
    public List<Thread> startedSinceAndStillAlive(Duration grace) {
        long deadline = System.nanoTime() + grace.toNanos();
        List<Thread> left = startedSinceAndStillAlive();
        while (!left.isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            left = startedSinceAndStillAlive();
        }
        return left;
    }

    private List<Thread> startedSinceAndStillAlive() {
        List<Thread> left = new ArrayList<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && !threads.contains(thread)) {
                left.add(thread);
            }
        }
        left.sort(Comparator.comparing(Thread::getName));
        return left;
    }

    /** One line per thread: name, daemon or not, state. */
    public static String describe(List<Thread> threads) {
        StringBuilder text = new StringBuilder();
        for (Thread thread : threads) {
            text.append("\n  ").append(thread.getName()).append(thread.isDaemon() ? " (daemon, " : " (NOT daemon, ")
                    .append(thread.getState()).append(')');
        }
        return text.toString();
    }
}
