package io.github.cmh.dhc.apps.dhcb;

import io.github.cmh.dhc.embed.EmbedSettings;
import io.github.cmh.dhc.embed.EmbeddedDeephaven;
import io.github.cmh.dhc.embed.UpstreamSettings;

/**
 * Role B, as a host sees it: an instance that subscribes to the tables of instance A and derives
 * its own. The Groovy module {@value #MODULE} lists the tables; everything else is the
 * framework's {@code RemoteTables}, so the role itself has no Java of its own to run.
 *
 * <pre>{@code
 * EmbeddedDeephaven deephaven = RoleB.bind(EmbeddedDeephaven.builder().settings(settings), settings).build();
 * deephaven.start();     // subscribes; waits for A if A is not up yet
 * }</pre>
 */
public final class RoleB {

    /** Id of the Groovy application-mode module in this jar. */
    public static final String MODULE = "dhc-b";

    /** The instance this role subscribes to. Its address is {@code DHC_UPSTREAM_DHC_A_URL}. */
    public static final String UPSTREAM = "dhc-a";

    private RoleB() {}

    /** Tells the instance where A is; the module reads it as {@code host.remote("dhc-a")}. */
    public static EmbeddedDeephaven.Builder bind(EmbeddedDeephaven.Builder builder, EmbedSettings settings) {
        return builder.upstream(UpstreamSettings.from(settings, UPSTREAM));
    }
}
