package io.github.cmh.dhc.orders;

import java.time.Duration;
import java.time.Instant;

/**
 * Everything that decides which executions the {@link MockExecGenerator} produces. Two generators
 * with equal configurations produce equal events, which is what lets {@link ExecOracle} say in
 * advance what every table has to contain.
 *
 * @param seed seed of the random choices
 * @param openOrders number of orders being filled at any moment
 * @param startTime {@code TransactTime} of the first execution
 * @param interval time between two executions, as recorded in {@code TransactTime}
 */
public record GeneratorConfig(long seed, int openOrders, Instant startTime, Duration interval) {

    public GeneratorConfig {
        if (openOrders < 1) {
            throw new IllegalArgumentException("openOrders must be at least 1, but it is " + openOrders);
        }
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("interval must be positive, but it is " + interval);
        }
    }

    /** A configuration for a feed of {@code eventsPerSecond}. */
    public static GeneratorConfig of(long seed, int openOrders, Instant startTime, double eventsPerSecond) {
        if (!(eventsPerSecond > 0)) {
            throw new IllegalArgumentException("eventsPerSecond must be positive, but it is " + eventsPerSecond);
        }
        return new GeneratorConfig(seed, openOrders, startTime, Duration.ofNanos(Math.max(1, Math.round(1e9 / eventsPerSecond))));
    }
}
