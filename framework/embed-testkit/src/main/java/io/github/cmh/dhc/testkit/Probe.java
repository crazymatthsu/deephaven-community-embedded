package io.github.cmh.dhc.testkit;

import io.deephaven.client.impl.ClientConfig;
import io.deephaven.client.impl.FlightSession;
import io.deephaven.client.impl.FlightSessionFactoryConfig;
import io.deephaven.client.impl.ScopeId;
import io.deephaven.client.impl.SessionConfig;
import io.deephaven.uri.DeephavenTarget;
import java.io.PrintStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;

/**
 * Looks at a table of a running instance from outside, as any client would: over the network,
 * with the instance's own login rules. Tests use it to check an instance they did not start in
 * their own JVM, and {@code scripts/smoke.sh} (phase P6) uses it to compare two instances.
 *
 * <pre>
 * java ... io.github.cmh.dhc.testkit.Probe dh+plain://localhost:10000 orders --sum CumQty
 * rows=242
 * sum.CumQty=124000
 * </pre>
 *
 * <p>The pre-shared key, when the instance asks for one, comes from the environment variable
 * {@value #KEY_VARIABLE}, never from the command line, where other users of the machine could
 * read it. The JVM needs the same flags as an embedded Deephaven (finding F3).
 */
public final class Probe {

    /** Environment variable with the instance's pre-shared key. */
    public static final String KEY_VARIABLE = "DHC_PROBE_PSK";

    private static final String PSK_HANDLER = "io.deephaven.authentication.psk.PskAuthenticationHandler";

    /** What a table contained at the moment it was read. */
    public record Result(long rows, Map<String, Number> sums) {
    }

    private Probe() {}

    /** Exits with 0 and prints the result, or with 1 and one line that says what went wrong. */
    public static void main(String[] args) {
        // An empty variable is "no key": a script that has none to give still sets the name.
        Optional<String> key = Optional.ofNullable(System.getenv(KEY_VARIABLE)).filter(value -> !value.isBlank());
        System.exit(run(args, key, System.out, System.err));
    }

    /** {@link #main} without the exit, for tests. */
    public static int run(String[] args, Optional<String> key, PrintStream out, PrintStream err) {
        List<String> columns = new ArrayList<>();
        if (args.length < 2) {
            err.println("usage: Probe <url> <table> [--sum <column>]...   for example: Probe dh+plain://localhost:10000"
                    + " orders --sum CumQty");
            return 2;
        }
        for (int i = 2; i < args.length; i++) {
            if (!"--sum".equals(args[i]) || i + 1 == args.length) {
                err.println("Probe: unexpected argument '" + args[i] + "'; the only option is --sum <column>");
                return 2;
            }
            columns.add(args[++i]);
        }
        try {
            Result result = read(args[0], key, args[1], columns);
            out.println("rows=" + result.rows());
            result.sums().forEach((column, sum) -> out.println("sum." + column + "=" + sum));
            return 0;
        } catch (RuntimeException e) {
            err.println("Probe: cannot read table '" + args[1] + "' at " + args[0] + ": " + rootMessage(e));
            return 1;
        }
    }

    /**
     * Reads a table once.
     *
     * @param url {@code dh+plain://host:port}, or {@code dh://host:port} for TLS
     * @param key the instance's pre-shared key, or empty for anonymous access
     * @param table the name the instance publishes the table under
     * @param sumColumns numeric columns to add up
     */
    public static Result read(String url, Optional<String> key, String table, List<String> sumColumns) {
        SessionConfig.Builder login = SessionConfig.builder();
        key.ifPresent(value -> login.authenticationTypeAndValue(PSK_HANDLER + " " + value));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dhc-probe");
            thread.setDaemon(true);
            return thread;
        });
        try (BufferAllocator allocator = new RootAllocator()) {
            FlightSessionFactoryConfig.Factory factory = FlightSessionFactoryConfig.builder()
                    .clientConfig(ClientConfig.builder().target(DeephavenTarget.of(URI.create(url))).build())
                    .sessionConfig(login.build())
                    .allocator(allocator)
                    .scheduler(scheduler)
                    .build()
                    .factory();
            try {
                FlightSession session = factory.newFlightSession();
                try {
                    return read(session, table, sumColumns);
                } finally {
                    session.close();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            } finally {
                factory.managedChannel().shutdownNow();
            }
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static Result read(FlightSession session, String table, List<String> sumColumns) {
        long rows = 0;
        Map<String, Number> sums = new LinkedHashMap<>();
        sumColumns.forEach(column -> sums.put(column, 0L));
        FlightStream stream = session.stream(new ScopeId(table));
        try {
            while (stream.next()) {
                VectorSchemaRoot batch = stream.getRoot();
                rows += batch.getRowCount();
                for (String column : sumColumns) {
                    FieldVector vector = batch.getVector(column);
                    if (vector == null) {
                        throw new IllegalArgumentException("the table has no column " + column);
                    }
                    for (int i = 0; i < batch.getRowCount(); i++) {
                        if (vector.getObject(i) instanceof Number value) {
                            sums.merge(column, value, Probe::add);
                        } else if (vector.getObject(i) != null) {
                            throw new IllegalArgumentException("column " + column + " is not numeric");
                        }
                    }
                }
            }
        } finally {
            close(stream);
        }
        return new Result(rows, sums);
    }

    private static void close(FlightStream stream) {
        try {
            stream.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // The rows are read; a failure to close the stream changes nothing about them.
        }
    }

    /** Whole numbers stay whole; anything else becomes a double. */
    private static Number add(Number total, Number value) {
        boolean whole = (total instanceof Long || total instanceof Integer) && (value instanceof Long
                || value instanceof Integer || value instanceof Short || value instanceof Byte);
        return whole ? (Number) (total.longValue() + value.longValue()) : (Number) (total.doubleValue() + value.doubleValue());
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.toString() : root.getMessage();
    }
}
