package io.github.cmh.dhc.embed;

/**
 * What Deephaven may change in the whole process when it starts (finding F5).
 *
 * <p>Deephaven was written to be the process. Started the way its own launcher starts it, it
 * replaces {@code System.out} and {@code System.err}, makes any uncaught exception in any thread
 * exit the JVM, and adds JVM shutdown hooks. That suits a host that is little more than a
 * {@code main} around Deephaven and surprises a business application. A host picks one of the two
 * defaults, or sets each switch.
 *
 * <p>Not covered, because Deephaven offers no way to leave them out: it routes
 * {@code java.util.logging} to SLF4J, sets the {@code deephaven.cacheDir}, {@code configDir} and
 * {@code dataDir} system properties, and keeps its configuration and update graph as process-wide
 * singletons (F10). docs/00-feasibility-spike.md lists them under F5.
 */
public final class SideEffectPolicy {

    private final boolean replaceStandardStreams;
    private final boolean exitJvmOnFatalError;
    private final boolean installShutdownHooks;

    private SideEffectPolicy(boolean replaceStandardStreams, boolean exitJvmOnFatalError, boolean installShutdownHooks) {
        this.replaceStandardStreams = replaceStandardStreams;
        this.exitJvmOnFatalError = exitJvmOnFatalError;
        this.installShutdownHooks = installShutdownHooks;
    }

    /**
     * Deephaven behaves as it does standalone. The default for a plain Java host, whose
     * {@code main} exists to run Deephaven.
     */
    public static SideEffectPolicy deephavenDefaults() {
        return new SideEffectPolicy(true, true, true);
    }

    /**
     * Deephaven changes nothing the host owns: the standard streams stay, an uncaught exception
     * is the host's business, and the host calls {@link EmbeddedDeephaven#stop()} itself. The
     * default for a Spring Boot host and for tests.
     */
    public static SideEffectPolicy hostKeepsControl() {
        return new SideEffectPolicy(false, false, false);
    }

    /**
     * Whether {@code System.out} and {@code System.err} are replaced by Deephaven's, which copy
     * everything printed into the log shown in the web console.
     */
    public boolean replaceStandardStreams() {
        return replaceStandardStreams;
    }

    /**
     * Whether a fatal error exits the JVM. With {@code true}, Deephaven's handler becomes the
     * default uncaught-exception handler, so an uncaught exception in any thread, the host's
     * included, exits with status 1. With {@code false} the handler that was there stays, and an
     * error the engine itself reports as fatal stops the embedded instance and not the JVM.
     */
    public boolean exitJvmOnFatalError() {
        return exitJvmOnFatalError;
    }

    /**
     * Whether Deephaven adds JVM shutdown hooks that stop it when the JVM exits. Without them the
     * host has to call {@link EmbeddedDeephaven#stop()}.
     */
    public boolean installShutdownHooks() {
        return installShutdownHooks;
    }

    public SideEffectPolicy withReplaceStandardStreams(boolean value) {
        return new SideEffectPolicy(value, exitJvmOnFatalError, installShutdownHooks);
    }

    public SideEffectPolicy withExitJvmOnFatalError(boolean value) {
        return new SideEffectPolicy(replaceStandardStreams, value, installShutdownHooks);
    }

    public SideEffectPolicy withInstallShutdownHooks(boolean value) {
        return new SideEffectPolicy(replaceStandardStreams, exitJvmOnFatalError, value);
    }

    @Override
    public String toString() {
        return "SideEffectPolicy{replaceStandardStreams=" + replaceStandardStreams + ", exitJvmOnFatalError="
                + exitJvmOnFatalError + ", installShutdownHooks=" + installShutdownHooks + "}";
    }
}
