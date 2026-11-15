package io.github.cmh.dhc.embed;

import io.deephaven.base.system.PrintStreamGlobals;
import io.deephaven.configuration.Configuration;
import io.deephaven.io.logger.Logger;
import io.deephaven.util.process.BaseProcessEnvironment;
import io.deephaven.util.process.DefaultFatalErrorReporter;
import io.deephaven.util.process.FatalErrorReporter;
import io.deephaven.util.process.FatalErrorReporterBase;
import io.deephaven.util.process.ProcessEnvironment;
import io.deephaven.util.process.ShutdownManagerImpl;
import java.io.PrintStream;
import java.util.function.BiConsumer;

/**
 * Deephaven's "process environment" for a host that keeps control of its own process (F5).
 *
 * <p>Internal: public only because Deephaven creates {@link Factory} by name, from the
 * configuration property {@code default.processEnvironmentFactory}. That property is Deephaven's
 * own extension point for exactly this, so nothing of Deephaven is patched.
 *
 * <p>Deephaven's default environment adds a JVM shutdown hook and reports every fatal error by
 * exiting the JVM. This one does each only if the {@link SideEffectPolicy} says so.
 */
public final class EmbeddedProcessEnvironment extends BaseProcessEnvironment {

    private static volatile SideEffectPolicy policy = SideEffectPolicy.deephavenDefaults();
    private static volatile BiConsumer<String, Throwable> fatalErrorListener = (message, error) -> {
    };

    /** The property Deephaven reads the factory's class name from. */
    static final String FACTORY_PROPERTY = "default.processEnvironmentFactory";

    /** Called before Deephaven initialises; the engine is one per process, so this is too. */
    static void configure(SideEffectPolicy newPolicy, BiConsumer<String, Throwable> newFatalErrorListener) {
        policy = newPolicy;
        fatalErrorListener = newFatalErrorListener;
    }

    private EmbeddedProcessEnvironment(FatalErrorReporter fatalErrorReporter, String mainClassName, Logger log) {
        super(new ShutdownManagerImpl(), fatalErrorReporter, mainClassName, log);
    }

    @Override
    public void onStartup() {
        log.info().append(mainClassName).append(": starting embedded, ").append(policy.toString()).endl();
        if (policy.installShutdownHooks()) {
            shutdownManager.addShutdownHookToRuntime();
        }
    }

    @Override
    public void onShutdown() {
        shutdownManager.maybeInvokeTasks();
    }

    /** Reports a fatal engine error to the host and leaves the JVM running. */
    private static final class HostFatalErrorReporter extends FatalErrorReporterBase {

        private final PrintStream err = PrintStreamGlobals.getErr();

        @Override
        protected void reportImpl(String message, Throwable throwable, boolean isFromUncaught) {
            err.println("Embedded Deephaven reported a fatal error: " + message);
            throwable.printStackTrace(err);
            fatalErrorListener.accept(message, throwable);
            if (isFromUncaught) {
                return;
            }
            // Deephaven's own reporter never returns to a caller that reported a fatal error.
            // Returning normally would let that caller carry on in a state it called fatal.
            throw new EmbeddedDeephavenException("Embedded Deephaven reported a fatal error: " + message, throwable);
        }
    }

    /** Created by Deephaven, by name. */
    public static final class Factory implements ProcessEnvironment.Factory {

        public Factory() {}

        @Override
        public ProcessEnvironment make(Configuration configuration, String mainClassName, Logger log) {
            FatalErrorReporter reporter =
                    policy.exitJvmOnFatalError() ? new DefaultFatalErrorReporter() : new HostFatalErrorReporter();
            return new EmbeddedProcessEnvironment(reporter, mainClassName, log);
        }
    }
}
