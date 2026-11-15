package io.github.cmh.dhc.embed;

import io.deephaven.client.impl.BarrageSession;
import io.deephaven.engine.table.ColumnDefinition;
import io.deephaven.engine.table.Table;
import io.deephaven.engine.table.TableDefinition;
import io.deephaven.engine.table.impl.BaseTable;
import io.deephaven.extensions.barrage.BarrageSubscriptionOptions;
import io.deephaven.qst.table.TicketTable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The tables of another Deephaven instance (the upstream), subscribed to from this one. Scripts
 * get it with {@code host.remote("<upstream id>")}.
 *
 * <pre>{@code
 * a           = host.remote("dhc-a")
 * orders_a    = a.subscribe("orders")       // a live copy of the upstream's table
 * feed_status = a.statusTable()             // is the upstream connected, since when
 * }</pre>
 *
 * <p><b>When the upstream goes away.</b> Deephaven marks a subscribed table, and every table
 * derived from it, as failed within about half a second, and they stay failed when the upstream
 * comes back: a table cannot be pointed at a new source. So this class watches its subscriptions.
 * When they fail it reports {@link State#DISCONNECTED}, keeps trying to reach the upstream, and
 * once that works it has the application scripts run again, which subscribe anew and rebuild
 * what they derive. A script that uses {@code host.remote} therefore has to be a list of
 * definitions that can be run more than once.
 *
 * <p>What that means for whoever reads the tables: look a table up by name each time
 * ({@code EmbeddedDeephaven.table(name)}), because after a reconnect the name refers to a new
 * table. A table object obtained before the outage, a panel open in the web IDE included, keeps
 * showing the last rows it had and has to be reopened. The upstream's own history is its own
 * business: if it restarted empty, the new tables start empty.
 */
public final class RemoteTables implements AutoCloseable {

    /** What the status table says about the connection. */
    public enum State {
        /** Not connected yet, or trying again. */
        CONNECTING,
        /** Subscriptions are live. */
        CONNECTED,
        /** The upstream went away; the tables hold their last rows and are not updated. */
        DISCONNECTED
    }

    /** One row of the status table. */
    private record Status(String upstream, String url, State state, Instant since, int subscriptions, long reconnects,
            String lastError, Instant timestamp) {
    }

    /** The status table's columns. */
    public static final TableDefinition STATUS = TableDefinition.of(
            ColumnDefinition.ofString("Upstream"),
            ColumnDefinition.ofString("Url"),
            ColumnDefinition.ofString("State"),
            ColumnDefinition.ofTime("Since"),
            ColumnDefinition.ofInt("Subscriptions"),
            ColumnDefinition.ofLong("Reconnects"),
            ColumnDefinition.ofString("LastError"),
            ColumnDefinition.ofTime("Timestamp"));

    private static final Logger log = LoggerFactory.getLogger(RemoteTables.class);

    private static final BarrageSubscriptionOptions OPTIONS =
            BarrageSubscriptionOptions.builder().useDeephavenNulls(true).build();

    private static final long WATCH_INTERVAL_MILLIS = 250;
    private static final long STATUS_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final UpstreamSettings settings;
    private final Supplier<BarrageSession> sessions;
    private final Runnable rebuild;
    private final RecordPublisher<Status> statusPublisher;

    private final List<Table> subscriptions = new ArrayList<>();
    private BarrageSession session;
    private Table statusTable;
    private State state = State.CONNECTING;
    private Instant since = Instant.now();
    private long reconnects;
    private String lastError;

    private volatile boolean closed;
    private Thread watcher;

    /**
     * @param sessions opens a session to the upstream, or throws
     * @param rebuild runs the application scripts again, after a reconnect
     */
    RemoteTables(UpstreamSettings settings, Supplier<BarrageSession> sessions, Runnable rebuild) {
        this.settings = settings;
        this.sessions = sessions;
        this.rebuild = rebuild;
        this.statusPublisher = RecordPublisher.<Status>builder("upstream_status_" + settings.id(), STATUS)
                .column("Upstream", Status::upstream)
                .column("Url", Status::url)
                .column("State", Status::state)
                .column("Since", Status::since)
                .column("Subscriptions", Status::subscriptions)
                .column("Reconnects", Status::reconnects)
                .column("LastError", Status::lastError)
                .column("Timestamp", Status::timestamp)
                .build();
    }

    public String id() {
        return settings.id();
    }

    public synchronized State state() {
        return state;
    }

    /** How often the connection has been made anew after being lost. */
    public synchronized long reconnects() {
        return reconnects;
    }

    /**
     * A live copy of a table the upstream publishes under this name. At start it waits for the
     * upstream, up to the configured time.
     *
     * @throws EmbeddedDeephavenException if the upstream does not answer, refuses the login, or has no such table
     */
    public synchronized Table subscribe(String tableName) {
        BarrageSession current = connected();
        try {
            Table table = current.subscribe(TicketTable.fromQueryScopeField(tableName), OPTIONS)
                    .entireTable()
                    .get(settings.connectTimeout().toMillis(), TimeUnit.MILLISECONDS);
            subscriptions.add(table);
            return table;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddedDeephavenException("Interrupted while subscribing to " + describe(tableName), e);
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            throw new EmbeddedDeephavenException("Cannot subscribe to " + describe(tableName) + ": " + cause, cause);
        }
    }

    /**
     * One row about the connection: {@code State} (CONNECTING, CONNECTED, DISCONNECTED),
     * {@code Since}, the number of {@code Subscriptions} and {@code Reconnects}, the
     * {@code LastError}, and a {@code Timestamp} that moves once a second while this instance is
     * alive. It is this instance's own table, so it keeps working while the upstream is down.
     */
    public synchronized Table statusTable() {
        if (statusTable == null) {
            statusTable = statusPublisher.blinkTable().lastBy("Upstream");
            publishStatus();
        }
        return statusTable;
    }

    private String describe(String tableName) {
        return "table '" + tableName + "' of upstream " + settings.id() + " at " + settings.url();
    }

    private BarrageSession connected() {
        if (session == null) {
            session = connectWithin(settings.connectTimeout().toNanos());
            enter(State.CONNECTED, null);
        }
        return session;
    }

    /** Keeps trying until the upstream answers or the time is up. A refused key is not retried. */
    private BarrageSession connectWithin(long nanos) {
        long deadline = System.nanoTime() + nanos;
        RuntimeException last;
        do {
            try {
                BarrageSession opened = sessions.get();
                log.info("Connected to upstream {} at {}", settings.id(), settings.url());
                return opened;
            } catch (RuntimeException e) {
                last = e;
                if (isRefusedLogin(e)) {
                    throw refusedLogin(e);
                }
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (!closed && System.nanoTime() < deadline);
        throw new EmbeddedDeephavenException("Upstream " + settings.id() + " at " + settings.url()
                + " did not answer within " + TimeUnit.NANOSECONDS.toSeconds(nanos) + " seconds. Is it running, and is "
                + UpstreamSettings.urlKey(settings.id()) + " right? Last error: " + last, last);
    }

    private EmbeddedDeephavenException refusedLogin(Throwable cause) {
        String key = UpstreamSettings.pskKey(settings.id());
        return new EmbeddedDeephavenException("Upstream " + settings.id() + " at " + settings.url()
                + " refused the login. " + (settings.preSharedKey().isPresent()
                        ? "The key in " + key + " is not the upstream's key."
                        : "It asks for a pre-shared key; give it in " + key + "."),
                cause);
    }

    private static boolean isRefusedLogin(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String text = String.valueOf(cause.getMessage());
            if (text.contains("UNAUTHENTICATED") || text.contains("PERMISSION_DENIED")) {
                return true;
            }
        }
        return false;
    }

    /** Starts watching the subscriptions. Called once this instance has started. */
    synchronized void startWatching() {
        if (watcher != null || closed) {
            return;
        }
        watcher = new Thread(this::watch, "dhc-upstream-" + settings.id());
        watcher.setDaemon(true);
        watcher.start();
    }

    private void watch() {
        long statusDue = System.nanoTime();
        while (!closed) {
            try {
                if (lost()) {
                    reconnect();
                }
                if (System.nanoTime() >= statusDue) {
                    publishStatus();
                    statusDue = System.nanoTime() + STATUS_INTERVAL_NANOS;
                }
                Thread.sleep(WATCH_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("Watching upstream {} failed and goes on: {}", settings.id(), e.toString());
            }
        }
    }

    /** True once a subscription has failed; from then on the old session and its tables are given up. */
    private synchronized boolean lost() {
        if (state == State.CONNECTED) {
            boolean failed = subscriptions.stream().anyMatch(table -> table instanceof BaseTable<?> base && base.isFailed());
            if (!failed) {
                return false;
            }
            log.warn("Lost upstream {} at {}: its tables are no longer updated. Trying to reconnect.", settings.id(),
                    settings.url());
            closeSession();
            subscriptions.clear();
            enter(State.DISCONNECTED, "the connection was lost");
        }
        return state != State.CONNECTED && session == null && !subscriptionsNeverMade();
    }

    /** Before the first subscription there is nothing to lose and nothing to rebuild. */
    private boolean subscriptionsNeverMade() {
        return state == State.CONNECTING && reconnects == 0 && lastError == null;
    }

    /** One attempt. On success the scripts run again and subscribe anew. */
    private void reconnect() {
        BarrageSession opened;
        try {
            opened = sessions.get();
        } catch (RuntimeException e) {
            synchronized (this) {
                lastError = isRefusedLogin(e) ? refusedLogin(e).getMessage() : "not reachable: " + rootMessage(e);
            }
            return;
        }
        synchronized (this) {
            session = opened;
            reconnects++;
            enter(State.CONNECTING, lastError);
        }
        try {
            // Not under this object's lock: the scripts call subscribe(), from the thread that runs them.
            rebuild.run();
            synchronized (this) {
                enter(State.CONNECTED, null);
            }
            log.info("Reconnected to upstream {} at {}; the application scripts ran again.", settings.id(),
                    settings.url());
        } catch (RuntimeException e) {
            log.warn("Reconnected to upstream {} but rebuilding the tables failed; trying again: {}", settings.id(),
                    e.toString());
            synchronized (this) {
                closeSession();
                subscriptions.clear();
                enter(State.DISCONNECTED, "rebuilding the tables failed: " + rootMessage(e));
            }
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return String.valueOf(root.getMessage() == null ? root : root.getMessage());
    }

    private void enter(State newState, String error) {
        if (state != newState) {
            since = Instant.now();
        }
        state = newState;
        lastError = error;
        publishStatus();
    }

    private synchronized void publishStatus() {
        statusPublisher.offer(new Status(settings.id(), settings.url(), state, since, subscriptions.size(), reconnects,
                lastError, Instant.now()));
    }

    private void closeSession() {
        if (session != null) {
            try {
                session.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.debug("Closing the session to upstream {} failed: {}", settings.id(), e.toString());
            }
            session = null;
        }
    }

    @Override
    public void close() {
        closed = true;
        Thread watching;
        synchronized (this) {
            watching = watcher;
            closeSession();
        }
        if (watching != null) {
            watching.interrupt();
        }
    }
}
