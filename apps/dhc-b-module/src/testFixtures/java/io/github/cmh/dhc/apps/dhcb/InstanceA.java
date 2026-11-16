package io.github.cmh.dhc.apps.dhcb;

import io.github.cmh.dhc.embed.UpstreamSettings;
import io.github.cmh.dhc.orders.ExecEvent;
import io.github.cmh.dhc.orders.ExecOracle;
import io.github.cmh.dhc.orders.ExecTables;
import io.github.cmh.dhc.orders.GeneratorConfig;
import io.github.cmh.dhc.orders.MockExecGenerator;
import io.github.cmh.dhc.testkit.ChildApp;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Instance A for the two-process tests: the installed {@code dhc-a-java}, run as a process of its
 * own with a fixed seed and an event limit, and what its tables must then contain. Shared, as
 * test fixtures of this module, by every test that puts a role B beside a real A.
 *
 * <p>The build passes the distribution's directory as the system property {@code dhc.a.install}.
 */
public final class InstanceA {

    public static final int EVENTS = 600;
    public static final Duration PATIENCE = Duration.ofSeconds(90);

    /** Made up for the tests; A is told it and B has to present it. */
    public static final String KEY = "integration-test-key-not-a-secret";

    private static final Path INSTALL = Path.of(System.getProperty("dhc.a.install"));
    private static final String MAIN = "io.github.cmh.dhc.apps.dhca.java.DhcAJavaApp";
    private static final Instant START = Instant.parse("2026-01-05T14:30:00Z");
    private static final GeneratorConfig CONFIG = GeneratorConfig.of(42, 12, START, 2_000.0);

    public static final ExecOracle ORACLE = ExecOracle.after(CONFIG, EVENTS);

    public final int port = ChildApp.freePort();
    private final boolean withKey;
    private ChildApp process;

    private InstanceA(boolean withKey) {
        this.withKey = withKey;
    }

    /** A, started and answering, with anonymous access. */
    public static InstanceA anonymous() {
        return new InstanceA(false).start();
    }

    /** A, started and answering, asking for {@link #KEY}. */
    public static InstanceA withKey() {
        return new InstanceA(true).start();
    }

    /** Starts A, again after a stop or kill, on the same port and with the same feed. */
    public InstanceA start() {
        try {
            ChildApp.Builder builder = ChildApp.distribution(INSTALL, MAIN)
                    .env("DHC_HTTP_PORT", String.valueOf(port))
                    .env("DHC_WORK_DIR", Files.createTempDirectory("dhc-a-child-").toString())
                    .env("DHC_GEN_SEED", "42")
                    .env("DHC_GEN_RATE", "2000")
                    .env("DHC_GEN_ORDERS", "12")
                    .env("DHC_GEN_EVENT_LIMIT", String.valueOf(EVENTS))
                    .env("DHC_GEN_START_TIME", START.toString());
            if (withKey) {
                builder.env("DHC_AUTH_MODE", "psk").env("DHC_AUTH_PSK", KEY);
            } else {
                builder.env("DHC_AUTH_MODE", "anonymous");
            }
            process = builder.start();
            process.awaitOutput("is up:", PATIENCE);
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String url() {
        return "dh+plain://localhost:" + port;
    }

    /** How B is told about this A. */
    public UpstreamSettings upstream(Optional<String> key) {
        return new UpstreamSettings(RoleB.UPSTREAM, url(), key, Duration.ofSeconds(60));
    }

    /** As a crash would end it. */
    public void kill() {
        process.kill();
    }

    /** As Ctrl-C would end it. */
    public void stop() {
        process.stop();
    }

    public void close() {
        if (process != null) {
            process.close();
        }
    }

    /** A's {@code orders} once the limit is reached: the latest execution of every order. */
    public static List<Map<String, Object>> expectedOrders() {
        return ORACLE.lastExecByOrder().values().stream().map(ExecTables::row).toList();
    }

    /** A's {@code execs} once the limit is reached: every execution, in order. */
    public static List<Map<String, Object>> expectedExecs() {
        MockExecGenerator generator = new MockExecGenerator(CONFIG);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < EVENTS; i++) {
            rows.add(ExecTables.row(generator.next()));
        }
        return rows;
    }

    /** B's {@code cum_by_symbol}: the filled quantity per symbol, symbols in the order they first appear. */
    public static List<Map<String, Object>> expectedCumBySymbol() {
        Map<String, Long> totals = new LinkedHashMap<>();
        for (ExecEvent last : ORACLE.lastExecByOrder().values()) {
            totals.merge(last.symbol(), last.cumQty(), Long::sum);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        totals.forEach((symbol, cumQty) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("Symbol", symbol);
            row.put("CumQty", cumQty);
            rows.add(row);
        });
        return rows;
    }
}
