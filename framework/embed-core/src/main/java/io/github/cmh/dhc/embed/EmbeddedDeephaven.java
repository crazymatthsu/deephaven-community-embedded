package io.github.cmh.dhc.embed;

import io.deephaven.configuration.Configuration;
import io.deephaven.engine.context.ExecutionContext;
import io.deephaven.engine.table.Table;
import io.deephaven.engine.table.impl.OperationInitializationThreadPool;
import io.deephaven.engine.updategraph.impl.PeriodicUpdateGraph;
import io.deephaven.engine.util.ScriptSession;
import io.deephaven.server.runner.DeephavenApiServer;
import io.deephaven.server.runner.MainHelper;
import io.deephaven.server.util.Scheduler;
import io.deephaven.util.process.ProcessEnvironment;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deephaven Community Core running inside this JVM. The one way a host starts and stops it.
 *
 * <pre>{@code
 * EmbeddedDeephaven dh = EmbeddedDeephaven.builder()
 *         .settings(EmbedSettings.load("dhc-a"))
 *         .bind("orderCache", cache)          // a Groovy module reads it as host.orderCache
 *         .build();
 * dh.start();                                 // returns once the application modules have run
 * Table hello = dh.table("hello");
 * long rows = dh.engine().call(hello::size);
 * dh.stop();
 * }</pre>
 *
 * <p>One per JVM (finding F10): Deephaven's configuration and update graph belong to the process,
 * so a second instance, or a restart of this one, needs a new JVM.
 */
public final class EmbeddedDeephaven implements AutoCloseable {

    private enum State {
        NEW, STARTING, RUNNING, STOPPED, FAILED
    }

    private static final Logger log = LoggerFactory.getLogger(EmbeddedDeephaven.class);

    /** Set by the first start in this JVM and never cleared (F10). */
    private static final AtomicBoolean STARTED_IN_THIS_JVM = new AtomicBoolean();

    private final EmbedSettings settings;
    private final SideEffectPolicy sideEffects;
    private final HostBindings hostBindings;
    private final Class<?> hostClass;

    private State state = State.NEW;
    private volatile DeephavenApiServer server;
    private volatile ScriptSession scriptSession;
    private volatile ExecutionContext executionContext;
    private volatile Scheduler scheduler;
    private volatile Engine engine;
    private volatile int httpPort = -1;
    private final AtomicReference<Throwable> fatalError = new AtomicReference<>();

    private EmbeddedDeephaven(Builder builder) {
        this.settings = Objects.requireNonNull(builder.settings, "settings");
        this.sideEffects = builder.sideEffects;
        this.hostClass = builder.hostClass;
        Map<String, Object> bindings = new LinkedHashMap<>(builder.bindings);
        bindings.putIfAbsent("appId", settings.appId());
        this.hostBindings = new HostBindings(bindings);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts Deephaven and returns once its application modules have run and the web UI answers.
     *
     * @throws EmbeddedDeephavenException with a message that says what to change, when a
     *         preflight check fails or Deephaven does not start
     */
    public synchronized void start() {
        if (state != State.NEW) {
            throw new EmbeddedDeephavenException("This instance was already started (state " + state + ")."
                    + " An embedded Deephaven cannot be restarted in the same JVM (F10).");
        }
        Preflight.check(settings);
        if (!STARTED_IN_THIS_JVM.compareAndSet(false, true)) {
            throw new EmbeddedDeephavenException("An embedded Deephaven was already started in this JVM."
                    + " Deephaven's configuration and update graph belong to the process (F10), so a second"
                    + " instance needs a JVM of its own.");
        }
        state = State.STARTING;
        Path applicationDir = AppModules.prepare(settings, classLoader());
        applyToDeephavenConfiguration(applicationDir);

        // Deephaven leaves an execution context open on the thread that starts it. On a thread of
        // its own that ends with the start, it never reaches a thread the host owns.
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread starter = new Thread(() -> {
            try {
                bootstrap();
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "dhc-embedded-start");
        starter.start();
        try {
            starter.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state = State.FAILED;
            throw new EmbeddedDeephavenException("Interrupted while Deephaven was starting.", e);
        }
        if (failure.get() != null) {
            state = State.FAILED;
            Throwable cause = failure.get();
            if (cause instanceof EmbeddedDeephavenException known) {
                throw known;
            }
            throw new EmbeddedDeephavenException("Deephaven did not start: " + cause, cause);
        }
        state = State.RUNNING;
        log.info("Embedded Deephaven '{}' is up: {}", settings.appId(), url());
    }

    private ClassLoader classLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : EmbeddedDeephaven.class.getClassLoader();
    }

    /** Deephaven reads its settings from its configuration, which system properties override. */
    private void applyToDeephavenConfiguration(Path applicationDir) {
        Path workDir = settings.workDir().toAbsolutePath();
        System.setProperty("deephaven.console.type", "groovy");
        System.setProperty("http.host", settings.httpHost());
        System.setProperty("http.port", Integer.toString(settings.httpPort()));
        System.setProperty("deephaven.application.dir", applicationDir.toString());
        // F10: by default every instance on a machine shares one cache of compiled formulas.
        System.setProperty("deephaven.cacheDir", workDir.resolve("cache").toString());
        System.setProperty("deephaven.configDir", workDir.resolve("config").toString());
        System.setProperty("deephaven.dataDir", workDir.resolve("data").toString());
        if (System.getProperty(STORAGE_PATH_DEFAULTS) == null) {
            storagePathDefaults(File.separatorChar).ifPresent(paths -> System.setProperty(STORAGE_PATH_DEFAULTS, paths));
        }
        switch (settings.authMode()) {
            case ANONYMOUS -> {
                System.setProperty("AuthHandlers", "io.deephaven.auth.AnonymousAuthenticationHandler");
                log.warn("Embedded Deephaven '{}' runs WITHOUT AUTHENTICATION: anyone who can reach {}:{} can read"
                        + " every table and run code in this JVM. For development only; set {}=psk elsewhere.",
                        settings.appId(), settings.httpHost(), settings.httpPort(), EmbedSettings.AUTH_MODE);
            }
            case PSK -> {
                System.setProperty("AuthHandlers", "io.deephaven.authentication.psk.PskAuthenticationHandler");
                settings.preSharedKey().ifPresent(key -> System.setProperty("authentication.psk", key));
            }
        }
    }

    /** Deephaven's property for the directories its storage service creates at start. */
    static final String STORAGE_PATH_DEFAULTS = "storage.path.defaults";

    /**
     * Deephaven 42.4 does not start on Windows as shipped (finding F11). Its storage service
     * creates {@code /layouts} and {@code /notebooks} below its storage directory, and drops the
     * leading {@code /} only when that is the platform's file separator. On Windows it is not, the
     * path then points outside the storage directory, and the service refuses it with "Invalid
     * path: /layouts" before the server is up. Naming the two directories without the slash gets
     * the server started.
     *
     * @return the value to give Deephaven, or empty where the default works
     */
    static Optional<String> storagePathDefaults(char fileSeparator) {
        return fileSeparator == '/' ? Optional.empty() : Optional.of("layouts,notebooks");
    }

    private void bootstrap() throws Exception {
        PrintStream out = System.out;
        PrintStream err = System.err;
        Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();

        boolean deephavenOwnsTheProcess = sideEffects.exitJvmOnFatalError() && sideEffects.installShutdownHooks();
        if (!deephavenOwnsTheProcess) {
            EmbeddedProcessEnvironment.configure(sideEffects, this::onFatalError);
            System.setProperty(EmbeddedProcessEnvironment.FACTORY_PROPERTY,
                    EmbeddedProcessEnvironment.Factory.class.getName());
        }

        Configuration configuration = MainHelper.init(new String[0], hostClass, sideEffects.installShutdownHooks());
        if (!sideEffects.exitJvmOnFatalError()) {
            // MainHelper.init makes Deephaven's reporter the default handler; give the host's back.
            Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        }

        // The component puts `host` into the script session just before the application scripts run.
        EmbeddedComponentFactory.EmbeddedComponent component =
                new EmbeddedComponentFactory(hostBindings).build(configuration);

        DeephavenApiServer started = component.getServer().run();
        if (!sideEffects.replaceStandardStreams()) {
            System.setOut(out);
            System.setErr(err);
        }
        this.scriptSession = component.scriptSession().get();
        this.executionContext = component.executionContext().get();
        this.scheduler = component.scheduler();
        this.engine = new Engine(executionContext, started.getUpdateGraph());
        this.httpPort = started.server().getPort();
        this.server = started;
    }

    private void onFatalError(String message, Throwable error) {
        fatalError.compareAndSet(null, error);
        log.error("Embedded Deephaven '{}' reported a fatal error and is being stopped: {}", settings.appId(), message,
                error);
        Thread stopper = new Thread(this::stop, "dhc-embedded-stop-after-fatal-error");
        stopper.setDaemon(true);
        stopper.start();
    }

    /**
     * Stops Deephaven: the server, its sessions and the update graph. Does nothing if it is not
     * running. The JVM cannot start another one afterwards (F10).
     */
    public synchronized void stop() {
        if (state != State.RUNNING) {
            return;
        }
        state = State.STOPPED;
        log.info("Stopping embedded Deephaven '{}'", settings.appId());
        // Deephaven registers how to stop itself as shutdown tasks; run them now instead of at JVM exit.
        ProcessEnvironment.getGlobalShutdownManager().maybeInvokeTasks();
        server.getUpdateGraph().<PeriodicUpdateGraph>cast().stop();
        // The shutdown tasks leave two thread pools running, because a standalone server exits
        // right after them. A host lives on, so end them here.
        if (executionContext.getOperationInitializer() instanceof OperationInitializationThreadPool pool) {
            pool.shutdown();
        }
        if (scheduler instanceof Scheduler.DelegatingImpl timers) {
            try {
                timers.shutdown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.warn("Deephaven's scheduler threads did not end in time: {}", e.getMessage());
            }
        }
    }

    /** Blocks until the server has stopped, for a host whose {@code main} has nothing else to do. */
    public void awaitStopped() throws InterruptedException {
        requireRunning().server.join();
    }

    /** Same as {@link #stop()}. */
    @Override
    public void close() {
        stop();
    }

    public synchronized boolean isRunning() {
        return state == State.RUNNING;
    }

    /** The error that stopped the instance, if the engine reported a fatal one. */
    public Throwable fatalError() {
        return fatalError.get();
    }

    /**
     * A table by the name a script published it under.
     *
     * @throws EmbeddedDeephavenException if nothing of that name exists, with the names that do
     */
    public Table table(String name) {
        ScriptSession session = requireRunning().scriptSession;
        Object value = session.getQueryScope().readParamValue(name, null);
        if (value instanceof Table table) {
            return table;
        }
        TreeSet<String> names = new TreeSet<>(session.getQueryScope().getParamNames());
        if (value == null) {
            throw new EmbeddedDeephavenException("There is no table '" + name + "'. Variables in scope: " + names);
        }
        throw new EmbeddedDeephavenException("'" + name + "' is a " + value.getClass().getName() + ", not a table.");
    }

    /** Safe access to tables for host code (F4). */
    public Engine engine() {
        return requireRunning().engine;
    }

    /** The port the web UI and API listen on; the real one when the settings asked for port 0. */
    public int httpPort() {
        return requireRunning().httpPort;
    }

    /** Address of the web IDE, without any key. */
    public String url() {
        return "http://" + settings.httpHost() + ":" + httpPort + "/ide/";
    }

    public EmbedSettings settings() {
        return settings;
    }

    public SideEffectPolicy sideEffects() {
        return sideEffects;
    }

    private EmbeddedDeephaven requireRunning() {
        if (server == null || engine == null) {
            throw new EmbeddedDeephavenException("Embedded Deephaven '" + settings.appId() + "' is not running."
                    + " Call start() first.");
        }
        return this;
    }

    public static final class Builder {
        private EmbedSettings settings;
        private SideEffectPolicy sideEffects = SideEffectPolicy.deephavenDefaults();
        private Class<?> hostClass = EmbeddedDeephaven.class;
        private final Map<String, Object> bindings = new LinkedHashMap<>();

        private Builder() {}

        public Builder settings(EmbedSettings settings) {
            this.settings = settings;
            return this;
        }

        /** What Deephaven may change in the process (F5). Deephaven's own behaviour unless set. */
        public Builder sideEffects(SideEffectPolicy sideEffects) {
            this.sideEffects = Objects.requireNonNull(sideEffects, "sideEffects");
            return this;
        }

        /** The host's main class; Deephaven names the process after it in its logs. */
        public Builder hostClass(Class<?> hostClass) {
            this.hostClass = Objects.requireNonNull(hostClass, "hostClass");
            return this;
        }

        /** Makes a host object visible to application scripts as {@code host.<name>}. */
        public Builder bind(String name, Object object) {
            if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new EmbeddedDeephavenException("A binding name has to be usable as host.<name> in a script."
                        + " Got: " + name);
            }
            bindings.put(name, Objects.requireNonNull(object, "object"));
            return this;
        }

        public EmbeddedDeephaven build() {
            if (settings == null) {
                throw new EmbeddedDeephavenException("EmbeddedDeephaven needs settings:"
                        + " builder().settings(EmbedSettings.load(\"<app id>\"))");
            }
            return new EmbeddedDeephaven(this);
        }
    }
}
