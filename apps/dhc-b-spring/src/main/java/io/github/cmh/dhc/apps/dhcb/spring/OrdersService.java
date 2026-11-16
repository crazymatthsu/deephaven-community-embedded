package io.github.cmh.dhc.apps.dhcb.spring;

import io.deephaven.api.ColumnName;
import io.deephaven.api.filter.FilterComparison;
import io.deephaven.api.literal.Literal;
import io.deephaven.engine.liveness.LivenessScopeStack;
import io.deephaven.engine.table.Table;
import io.deephaven.util.SafeCloseable;
import io.github.cmh.dhc.apps.dhcb.RoleB;
import io.github.cmh.dhc.embed.EmbeddedDeephaven;
import io.github.cmh.dhc.embed.Engine;
import io.github.cmh.dhc.embed.RemoteTables;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * What the business side of this application asks of role B's tables. This is host code reading
 * engine tables, so it follows the three rules the framework documents:
 *
 * <ul>
 * <li>every read goes through {@code engine()} (F4);</li>
 * <li>a table is looked up by name each time, because after a reconnect to A the name refers to
 * a new table (F13);</li>
 * <li>while A is away the tables hold their last rows and can be read, but nothing can be derived
 * from them. A summary can still be given, marked stale; a lookup cannot.</li>
 * </ul>
 */
@Service
public class OrdersService {

    private final EmbeddedDeephaven deephaven;

    public OrdersService(EmbeddedDeephaven deephaven) {
        this.deephaven = deephaven;
    }

    /**
     * The latest state of one order, as column name to value; empty when A has no such order.
     *
     * @throws FeedUnavailableException while A is away
     */
    @SuppressWarnings("try") // the liveness scope only has to be open
    public Optional<Map<String, Object>> order(String orderId) {
        RemoteTables feed = feed();
        if (feed.state() != RemoteTables.State.CONNECTED) {
            throw new FeedUnavailableException(describe(feed));
        }
        Table orders = deephaven.table("orders_a");
        Engine engine = deephaven.engine();
        try {
            return engine.call(() -> {
                // The filtered table lives as long as this scope: it is made, read and let go.
                try (SafeCloseable scope = LivenessScopeStack.open()) {
                    // A filter object, not a formula: the id comes from a URL and is never parsed as code.
                    Table match = orders.where(FilterComparison.eq(ColumnName.of("OrderID"), Literal.of(orderId)));
                    return engine.rows(match).stream().findFirst();
                }
            });
        } catch (IllegalStateException e) {
            // A went away between the check above and the filter: the table failed in that moment.
            throw new FeedUnavailableException(describe(feed));
        }
    }

    /** Totals over role B's tables, with what is known about the connection to A. */
    public Summary summary() {
        RemoteTables feed = feed();
        Table orders = deephaven.table("orders_a");
        Table openOrders = deephaven.table("open_orders");
        Table cumBySymbol = deephaven.table("cum_by_symbol");
        Engine engine = deephaven.engine();
        // What is known about the connection is read first: inside call() there is table work only.
        RemoteTables.State state = feed.state();
        Feed connection = new Feed(feed.id(), state.name(), feed.since().toString(), feed.reconnects(),
                feed.lastError().orElse(null));
        return engine.call(() -> {
            List<SymbolTotal> bySymbol = new ArrayList<>();
            long cumQty = 0;
            for (Map<String, Object> row : engine.rows(cumBySymbol)) {
                long quantity = ((Number) row.get("CumQty")).longValue();
                bySymbol.add(new SymbolTotal((String) row.get("Symbol"), quantity));
                cumQty += quantity;
            }
            return new Summary(connection, state != RemoteTables.State.CONNECTED, orders.size(), openOrders.size(),
                    cumQty, bySymbol);
        });
    }

    /** What the page needs to point its table widgets at this instance's Deephaven. */
    public Map<String, Object> embedding() {
        Map<String, Object> embedding = new LinkedHashMap<>();
        embedding.put("port", deephaven.httpPort());
        embedding.put("authentication", deephaven.settings().authMode().name().toLowerCase());
        embedding.put("tables", List.of("orders_a", "cum_by_symbol"));
        return embedding;
    }

    private RemoteTables feed() {
        return deephaven.upstream(RoleB.UPSTREAM)
                .orElseThrow(() -> new FeedUnavailableException("The embedded Deephaven has not started."));
    }

    private static String describe(RemoteTables feed) {
        return "Instance " + feed.id() + " is " + feed.state() + " since " + feed.since()
                + feed.lastError().map(error -> " (" + error + ")").orElse("")
                + ". Orders can be looked up again once it is back.";
    }

    /**
     * @param stale true while A is away: the numbers are the last ones this instance saw
     * @param orders orders A has reported
     * @param openOrders of those, the ones not fully filled
     * @param cumQty filled quantity over all orders
     */
    public record Summary(Feed feed, boolean stale, long orders, long openOrders, long cumQty,
            List<SymbolTotal> bySymbol) {
    }

    public record Feed(String upstream, String state, String since, long reconnects, String lastError) {
    }

    public record SymbolTotal(String symbol, long cumQty) {
    }
}
