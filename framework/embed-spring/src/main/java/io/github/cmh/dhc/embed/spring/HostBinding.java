package io.github.cmh.dhc.embed.spring;

/**
 * A bean that application scripts can reach as {@code host.<name>}. Every bean of this type is
 * bound when the embedded Deephaven is built; nothing else has to be registered.
 *
 * <pre>{@code
 * @Component
 * class OrderCacheBinding implements HostBinding {
 *     public String bindingName() { return "orderCache"; }   // host.orderCache in a script
 *     public Object boundObject() { return cache; }
 * }
 * }</pre>
 *
 * <p>A bean that is itself what the scripts should see implements only {@link #bindingName()}.
 */
public interface HostBinding {

    /** The name after {@code host.}: letters, digits and {@code _}, not starting with a digit. */
    String bindingName();

    /** What the scripts get. The bean itself unless overridden. */
    default Object boundObject() {
        return this;
    }
}
