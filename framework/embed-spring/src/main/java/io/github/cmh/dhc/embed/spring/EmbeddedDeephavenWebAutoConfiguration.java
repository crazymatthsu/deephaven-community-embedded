package io.github.cmh.dhc.embed.spring;

import io.github.cmh.dhc.embed.EmbeddedDeephaven;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RestController;

/** The optional table listing, for a host that serves Spring MVC and asks for it. */
@AutoConfiguration(after = EmbeddedDeephavenAutoConfiguration.class)
@ConditionalOnClass(RestController.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(EmbeddedDeephaven.class)
@ConditionalOnProperty(name = "dhc.tables.endpoint", havingValue = "true")
public class EmbeddedDeephavenWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    TablesEndpoint dhcTablesEndpoint(EmbeddedDeephaven deephaven) {
        return new TablesEndpoint(deephaven);
    }
}
