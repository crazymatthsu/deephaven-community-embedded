package io.github.cmh.dhc.apps.dhca;

import io.github.cmh.dhc.embed.EmbedSettings;
import io.github.cmh.dhc.embed.EmbeddedDeephavenException;
import io.github.cmh.dhc.orders.GeneratorConfig;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * What role A can be told: how the mock feed behaves and how much history the instance keeps.
 * Scripts read it as {@code host.settings}.
 *
 * @param seed seed of the mock feed; the same seed gives the same executions
 * @param eventsPerSecond how fast executions arrive
 * @param openOrders how many orders are being filled at any moment
 * @param eventLimit the feed stops after this many executions; 0 means it never stops
 * @param ringCapacity how many executions the {@code execs} table keeps
 * @param startTime {@code TransactTime} of the first execution; now, unless {@code DHC_GEN_START_TIME} says otherwise
 */
public record RoleASettings(long seed, double eventsPerSecond, int openOrders, long eventLimit, int ringCapacity,
        Instant startTime) {

    public static final String GEN_SEED = "DHC_GEN_SEED";
    public static final String GEN_RATE = "DHC_GEN_RATE";
    public static final String GEN_ORDERS = "DHC_GEN_ORDERS";
    public static final String GEN_EVENT_LIMIT = "DHC_GEN_EVENT_LIMIT";
    public static final String RING_CAPACITY = "DHC_RING_CAPACITY";
    public static final String GEN_START_TIME = "DHC_GEN_START_TIME";

    public RoleASettings {
        require(eventsPerSecond > 0, GEN_RATE + " must be more than 0, but it is " + eventsPerSecond);
        require(openOrders >= 1, GEN_ORDERS + " must be at least 1, but it is " + openOrders);
        require(eventLimit >= 0, GEN_EVENT_LIMIT + " must be 0 (no limit) or more, but it is " + eventLimit);
        require(ringCapacity >= 1, RING_CAPACITY + " must be at least 1, but it is " + ringCapacity);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new EmbeddedDeephavenException(message);
        }
    }

    /** A feed of five executions a second over twenty open orders, without end, starting now. */
    public static RoleASettings defaults() {
        return new RoleASettings(42, 5.0, 20, 0, 10_000, Instant.now());
    }

    /** The defaults, changed by {@code DHC_GEN_*} and {@code DHC_RING_CAPACITY} where they are set. */
    public static RoleASettings from(EmbedSettings settings) {
        RoleASettings defaults = defaults();
        return new RoleASettings(
                settings.longValue(GEN_SEED, defaults.seed),
                settings.doubleValue(GEN_RATE, defaults.eventsPerSecond),
                Math.toIntExact(settings.longValue(GEN_ORDERS, defaults.openOrders)),
                settings.longValue(GEN_EVENT_LIMIT, defaults.eventLimit),
                Math.toIntExact(settings.longValue(RING_CAPACITY, defaults.ringCapacity)),
                settings.value(GEN_START_TIME).map(RoleASettings::parseStartTime).orElse(defaults.startTime));
    }

    private static Instant parseStartTime(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new EmbeddedDeephavenException(GEN_START_TIME + " must be a time like 2026-01-05T14:30:00Z, but it is '"
                    + text + "'.");
        }
    }

    /** What decides the events: the part of these settings the oracle needs too. */
    public GeneratorConfig generatorConfig() {
        return GeneratorConfig.of(seed, openOrders, startTime, eventsPerSecond);
    }
}
