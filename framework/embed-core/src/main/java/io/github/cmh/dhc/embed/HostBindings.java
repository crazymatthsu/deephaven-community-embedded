package io.github.cmh.dhc.embed;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The host's objects, as an application-mode script sees them: the variable {@code host}.
 *
 * <p>A Groovy script writes {@code host.orderCache}. Groovy turns a property it does not know
 * into a call of {@link #get(String)}, so this class needs nothing from Groovy.
 */
public final class HostBindings {

    /** Name of the variable in the script session. */
    public static final String VARIABLE = "host";

    private final Map<String, Object> bindings;
    private volatile Map<String, RemoteTables> remotes = Map.of();

    HostBindings(Map<String, Object> bindings) {
        this.bindings = Map.copyOf(bindings);
    }

    void remotes(Map<String, RemoteTables> remotes) {
        this.remotes = Map.copyOf(remotes);
    }

    /**
     * Another Deephaven instance whose tables this one subscribes to: {@code host.remote("dhc-a")}
     * in a script. The host names its upstreams with {@code EmbeddedDeephaven.builder().upstream(...)}.
     */
    public RemoteTables remote(String upstreamId) {
        RemoteTables remote = remotes.get(upstreamId);
        if (remote == null) {
            throw new EmbeddedDeephavenException("The host has no upstream called '" + upstreamId + "'. It has: "
                    + new TreeMap<>(remotes).keySet()
                    + ". Name upstreams with EmbeddedDeephaven.builder().upstream(UpstreamSettings).");
        }
        return remote;
    }

    /** The object bound under a name. Fails with the names that do exist when there is none. */
    public Object get(String name) {
        Object value = bindings.get(name);
        if (value == null) {
            throw new EmbeddedDeephavenException("The host binds nothing called '" + name + "'. It binds: " + names()
                    + ". Bind objects with EmbeddedDeephaven.builder().bind(name, object).");
        }
        return value;
    }

    public Set<String> names() {
        return new TreeMap<>(bindings).keySet();
    }

    @Override
    public String toString() {
        return "host" + names();
    }
}
