package io.github.cmh.dhc.embed.spring;

import io.github.cmh.dhc.embed.EmbedSettings;
import io.github.cmh.dhc.embed.EmbeddedDeephaven;

/**
 * Changes the embedded Deephaven before it is built, for what settings alone cannot say: a role's
 * upstream, a binding that is not a bean. Every bean of this type is applied, in bean order.
 *
 * <pre>{@code
 * @Bean
 * EmbeddedDeephavenCustomizer roleB() {
 *     return RoleB::bind;
 * }
 * }</pre>
 */
@FunctionalInterface
public interface EmbeddedDeephavenCustomizer {

    void customize(EmbeddedDeephaven.Builder builder, EmbedSettings settings);
}
