package io.github.cmh.dhc.embed.spring;

import io.github.cmh.dhc.embed.EmbedSettings;
import io.github.cmh.dhc.embed.EmbeddedDeephaven;
import io.github.cmh.dhc.embed.EmbeddedDeephavenException;
import io.github.cmh.dhc.embed.SideEffectPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * The embedded Deephaven as Spring beans.
 *
 * <p><b>Settings.</b> The same keys as everywhere else, read from Spring's {@code Environment}:
 * {@code dhc.http.port} in {@code application.properties} is the application's default,
 * {@code DHC_HTTP_PORT} in the environment overrides it, and {@code -Ddhc.http.port} overrides
 * both. That is Spring's own order, and the order a plain Java host applies. The application id
 * is {@code dhc.app.id}.
 *
 * <p><b>Side effects.</b> {@link SideEffectPolicy#hostKeepsControl()}: Spring keeps the standard
 * streams, its own handling of uncaught exceptions and its shutdown hook, and stops Deephaven
 * when the context closes.
 *
 * <p>A host replaces any of the beans by declaring its own.
 */
@AutoConfiguration
public class EmbeddedDeephavenAutoConfiguration {

    /** Property that names the application, for example {@code dhc-b}. */
    public static final String APP_ID = "dhc.app.id";

    @Bean
    @ConditionalOnMissingBean
    EmbedSettings embedSettings(Environment environment) {
        String appId = environment.getProperty(APP_ID);
        if (appId == null || appId.isBlank()) {
            throw new EmbeddedDeephavenException(APP_ID + " is not set. It names this application's embedded"
                    + " Deephaven, for example dhc-b; set it in application.properties.");
        }
        return EmbedSettings.from(appId.trim(), environment::getProperty);
    }

    @Bean
    @ConditionalOnMissingBean
    SideEffectPolicy sideEffectPolicy() {
        return SideEffectPolicy.hostKeepsControl();
    }

    @Bean
    @ConditionalOnMissingBean
    EmbeddedDeephaven embeddedDeephaven(EmbedSettings settings, SideEffectPolicy sideEffects,
            ObjectProvider<HostBinding> bindings, ObjectProvider<EmbeddedDeephavenCustomizer> customizers,
            ApplicationContext context) {
        EmbeddedDeephaven.Builder builder = EmbeddedDeephaven.builder().settings(settings).sideEffects(sideEffects);
        // Deephaven names the process after the host's main class in its logs.
        context.getBeansWithAnnotation(SpringBootApplication.class).values().stream()
                .findFirst()
                .ifPresent(application -> builder.hostClass(ClassUtils.getUserClass(application)));
        bindings.orderedStream().forEach(binding -> builder.bind(binding.bindingName(), binding.boundObject()));
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder, settings));
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    EmbeddedDeephavenLifecycle embeddedDeephavenLifecycle(EmbeddedDeephaven deephaven) {
        return new EmbeddedDeephavenLifecycle(deephaven);
    }
}
