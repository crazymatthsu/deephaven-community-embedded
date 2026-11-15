package io.github.cmh.dhc.embed;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import io.deephaven.appmode.ApplicationState;
import io.deephaven.client.impl.BarrageSessionFactoryConfig;
import io.deephaven.configuration.Configuration;
import io.deephaven.engine.context.ExecutionContext;
import io.deephaven.engine.util.ScriptSession;
import io.deephaven.server.auth.CommunityAuthorizationModule;
import io.deephaven.server.flightsql.FlightSqlModule;
import io.deephaven.server.jetty.JettyConfig;
import io.deephaven.server.jetty.JettyServerComponent;
import io.deephaven.server.jetty.JettyServerModule;
import io.deephaven.server.plugin.AuthorizationProviderAdapterModule;
import io.deephaven.server.runner.CommunityDefaultsModule;
import io.deephaven.server.runner.ComponentFactoryBase;
import io.deephaven.server.runner.TicketResolversFromServiceLoader;
import io.deephaven.server.session.ClientChannelFactoryModule;
import io.deephaven.server.session.ClientChannelFactoryModule.UserAgent;
import io.deephaven.server.session.SslConfigModule;
import io.deephaven.server.util.Scheduler;
import java.io.PrintStream;
import java.util.List;
import javax.inject.Provider;
import javax.inject.Singleton;

/**
 * Our own Dagger component over Deephaven's published modules (finding F1).
 *
 * <p>Upstream's ready-made factory lives in a module that is not published for 42.4, and its
 * Javadoc tells integrators to declare their own. Everything in this project that names a
 * Deephaven Dagger module is in this one class: when a Deephaven upgrade renames a module, this
 * is the file that stops compiling.
 */
final class EmbeddedComponentFactory extends ComponentFactoryBase<EmbeddedComponentFactory.EmbeddedComponent> {

    private final HostBindings hostBindings;

    EmbeddedComponentFactory(HostBindings hostBindings) {
        this.hostBindings = hostBindings;
    }

    @Override
    public EmbeddedComponent build(Configuration configuration, PrintStream out, PrintStream err) {
        return DaggerEmbeddedComponentFactory_EmbeddedComponent.builder()
                .withOut(out)
                .withErr(err)
                .withJettyConfig(JettyConfig.buildFromConfig(configuration).build())
                .withHostBindings(hostBindings)
                .build();
    }

    @Singleton
    @Component(modules = EmbeddedModule.class)
    interface EmbeddedComponent extends JettyServerComponent {

        /** The console's script session: where application scripts run and tables are published. */
        Provider<ScriptSession> scriptSession();

        /** What a host thread has to open before it may touch a table (F4). */
        Provider<ExecutionContext> executionContext();

        /** Deephaven's timer threads, exposed so that stopping can end them. */
        Scheduler scheduler();

        @Component.Builder
        interface Builder extends JettyServerComponent.Builder<Builder, EmbeddedComponent> {

            @BindsInstance
            Builder withHostBindings(HostBindings hostBindings);
        }
    }

    @Module(includes = {
            JettyServerModule.class,
            FlightSqlModule.class,
            TicketResolversFromServiceLoader.class,
            ClientChannelFactoryModule.class,
            SslConfigModule.class,
            CommunityAuthorizationModule.class,
            CommunityDefaultsModule.class,
            AuthorizationProviderAdapterModule.class,
    })
    interface EmbeddedModule {

        @Provides
        @UserAgent
        static String userAgent() {
            return BarrageSessionFactoryConfig.userAgent(List.of("dhc-embedded"));
        }

        /**
         * Deephaven runs every application factory before the application scripts, and only once
         * the script session can be created. That makes a factory the one place to put the
         * variable {@code host} into the session in time for the scripts.
         */
        @Provides
        @IntoSet
        static ApplicationState.Factory hostBindingsApplication(Provider<ScriptSession> scriptSession,
                HostBindings hostBindings) {
            return new HostBindingsApplication(scriptSession, hostBindings);
        }
    }

    private static final class HostBindingsApplication implements ApplicationState.Factory {
        private final Provider<ScriptSession> scriptSession;
        private final HostBindings hostBindings;

        HostBindingsApplication(Provider<ScriptSession> scriptSession, HostBindings hostBindings) {
            this.scriptSession = scriptSession;
            this.hostBindings = hostBindings;
        }

        @Override
        public ApplicationState create(ApplicationState.Listener listener) {
            scriptSession.get().getQueryScope().putParam(HostBindings.VARIABLE, hostBindings);
            return new ApplicationState(listener, "dhc-host", "Host bindings");
        }

        @Override
        public String toString() {
            return "dhc-host (" + hostBindings + ")";
        }
    }
}
