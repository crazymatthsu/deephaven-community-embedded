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

    HostBindings(Map<String, Object> bindings) {
        this.bindings = Map.copyOf(bindings);
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
