package io.github.cmh.dhc.apps.dhca;

import io.github.cmh.dhc.embed.EmbeddedDeephaven;
import io.github.cmh.dhc.orders.OrderCache;

/**
 * Role A, as a host sees it: mock order executions, the cache they update, and what the Groovy
 * module {@value #MODULE} needs from the host to show both.
 *
 * <pre>{@code
 * RoleA role = RoleA.create(RoleASettings.from(settings));
 * EmbeddedDeephaven deephaven = role.bind(EmbeddedDeephaven.builder().settings(settings)).build();
 * deephaven.start();     // the module builds its tables on the feed
 * role.start();          // executions begin to arrive
 * }</pre>
 *
 * <p>Any host can run it: a plain Java {@code main} and a Spring Boot application write the same
 * four lines.
 */
public final class RoleA implements AutoCloseable {

    /** Id of the Groovy application-mode module in this jar. */
    public static final String MODULE = "dhc-a";

    private final RoleASettings settings;
    private final OrderCache cache = new OrderCache();
    private final ExecFeed feed;
    private final OrderCacheView cacheView;

    private RoleA(RoleASettings settings) {
        this.settings = settings;
        this.cacheView = new OrderCacheView(cache);
        this.feed = new ExecFeed(settings, cache);
    }

    public static RoleA create(RoleASettings settings) {
        return new RoleA(settings);
    }

    /** Lends the module what it reads as {@code host.execFeed}, {@code host.orderCache} and {@code host.settings}. */
    public EmbeddedDeephaven.Builder bind(EmbeddedDeephaven.Builder builder) {
        return builder
                .bind("execFeed", feed)
                .bind("orderCache", cacheView)
                .bind("settings", settings);
    }

    /** Starts the feed and the cache statistics. Call it after {@code EmbeddedDeephaven.start()}. */
    public void start() {
        cacheView.start();
        feed.start();
    }

    /** Stops the feed and the cache statistics. */
    @Override
    public void close() {
        feed.close();
        cacheView.close();
    }

    /** The host's own cache: the application's state, with or without Deephaven. */
    public OrderCache cache() {
        return cache;
    }

    public ExecFeed feed() {
        return feed;
    }

    public RoleASettings settings() {
        return settings;
    }
}
