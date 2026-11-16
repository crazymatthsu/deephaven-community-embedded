/**
 * A Spring Boot starter around {@code embed-core}. A host that has this module on its classpath
 * gets an {@link io.github.cmh.dhc.embed.EmbeddedDeephaven} bean that starts and stops with the
 * application context, set up from the same {@code DHC_*} settings as a plain Java host.
 *
 * <ul>
 * <li>{@link io.github.cmh.dhc.embed.spring.EmbeddedDeephavenAutoConfiguration} makes the beans.</li>
 * <li>{@link io.github.cmh.dhc.embed.spring.HostBinding} is how a bean becomes {@code host.<name>}
 * in the application scripts.</li>
 * <li>{@link io.github.cmh.dhc.embed.spring.EmbeddedDeephavenCustomizer} is how a host adds what
 * settings cannot say, such as a role's upstream.</li>
 * <li>With the actuator on the classpath there is a health indicator {@code deephaven} and one
 * per upstream, named by the upstream's id.</li>
 * </ul>
 */
package io.github.cmh.dhc.embed.spring;
