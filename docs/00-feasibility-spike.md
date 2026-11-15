# 00. Feasibility spike: embedding Deephaven Community 42.4

Before planning, the embedding was tried end to end in two throwaway Gradle projects (not in this
repository). Everything below was **run**, on Deephaven Community Core 42.4, Gradle 9.2.0, OpenJDK
21 and Spring Boot 3.5.16, on Linux x86-64. The design ([01](01-design.md)) and the phases
([02](02-implementation-phases.md)) are built on these results.

## What was proven

| # | Question | Result |
|---|---|---|
| S1 | Can our own `main` start a Deephaven server in-process? | **Yes.** A plain Java `main` started the server, the Groovy console session and the web IDE on port 10000. |
| S2 | Does Application Mode work embedded, with a Groovy app module? | **Yes.** A `.app` file with `scriptType=groovy` under `deephaven.application.dir` ran at start-up and its tables were in scope. |
| S3 | Can Java code publish its own tables next to the Groovy ones? | **Yes**, through the script session's query scope, once the update-graph lock is taken (F4). |
| S4 | Can a second embedded instance subscribe to the first? | **Yes.** Instance B (port 10001) resolved `dh+plain://localhost:10000/scope/execs` from its Groovy module and built an aggregate on it. Row-level equality is left to the phase 4 test. |
| S5 | Can Spring Boot and Deephaven share one JVM? | **Yes.** Spring MVC on Tomcat (8080) served a REST endpoint that read a live Deephaven table while the Deephaven IDE answered on 10000. |
| S6 | Does the Spring Boot fat jar work? | **No** (F6). The extracted layout does (F7). |

## Findings that shape the design

**F1. The stock component factory is not published.** `CommunityComponentFactory` and `JettyMain`
live in upstream's `server/jetty-app` module, which is **not on Maven Central** for 42.4
(`deephaven-server-jetty-app` returns 404). The published jars do contain everything it is made
of: `JettyServerComponent`, `JettyServerModule`, `CommunityDefaultsModule`,
`CommunityAuthorizationModule`, `ComponentFactoryBase`, `MainHelper`. Embedding therefore means
declaring **our own Dagger component** over those modules, which is also what upstream's Javadoc
recommends for integrators. It is about forty lines and needs the Dagger annotation processor.
Our component can expose extra provisions; the spike exposed `Provider<ScriptSession>` and
`Provider<ExecutionContext>`, which is how Java code reaches the engine after start-up.

**F2. The server drags in the Kafka extension, which needs the Confluent repository.**
`deephaven-server-jetty` depends on `deephaven-extensions-kafka`, whose `io.confluent:*` and
`kafka-clients:*-ccs` artifacts are not on Maven Central. Resolution fails with Maven Central
alone. Excluding `io.deephaven:deephaven-extensions-kafka` resolves cleanly and the server starts.
The demo does not use Kafka, so the exclusion is the default; a company mirror of the Confluent
repository is the alternative.

**F3. JVM flags are mandatory and cannot be set from code.** Upstream's launcher passes
`--add-exports java.management/sun.management=ALL-UNNAMED`,
`--add-exports java.base/jdk.internal.misc=ALL-UNNAMED` and
`--add-opens java.base/java.nio=ALL-UNNAMED`. Every way of starting an app (start script,
`bootRun`, tests, container entry point) must pass them, so they are defined once in build logic.

**F4. Table operations from a host thread need the update-graph lock.** Creating a ticking table
from the `main` thread failed with `May not initiate serial table operations for update graph
DEFAULT`. Wrapping the call in `updateGraph.sharedLock().computeLocked(...)` inside an open
`ExecutionContext` fixed it. The framework must give host code one safe way to do this.

**F5. `MainHelper.init` changes the whole process.** It replaces `System.out` / `System.err`,
installs shutdown hooks and a default uncaught-exception handler that **shuts the JVM down**
(`Initiating shutdown due to: Uncaught exception in thread main`). That is right for a standalone
server and surprising inside a business application. The framework has to decide, per host, which
of these side effects to keep (phase 2 task).

*Investigated in phase P2*, by reading the 42.4 sources. The spike's description was partly
wrong: `MainHelper.init` does not replace the standard streams. What happens, and where:

| What changes in the process | Done by | Controlled by |
|---|---|---|
| `System.out` and `System.err` are replaced by streams that also feed the web console's log | `DeephavenApiServer.run()` (its log initialisation), not `MainHelper.init` | `replaceStandardStreams`: the originals are put back after start |
| The default uncaught-exception handler becomes Deephaven's, which exits the JVM with status 1 | `MainHelper.init` | `exitJvmOnFatalError`: the previous handler is put back |
| An error the engine itself reports as fatal exits the JVM | the process environment's fatal-error reporter | `exitJvmOnFatalError`: our reporter stops the embedded instance instead |
| A JVM shutdown hook that stops Deephaven, and a second one that stops Logback | the process environment's `onStartup`; `MainHelper.init` | `installShutdownHooks` |
| An execution context is opened, and left open, on the thread that starts the server | `DeephavenApiServer.run()` | always contained: the framework starts Deephaven on a thread of its own |
| `java.util.logging` is routed to SLF4J; `deephaven.cacheDir`, `configDir` and `dataDir` become system properties; configuration and update graph are process-wide singletons | `MainHelper.init` | not controllable |

The shutdown hook and the fatal-error behaviour belong to Deephaven's "process environment",
which it creates by class name from the configuration property
`default.processEnvironmentFactory`. A host that keeps control supplies its own through that
property, so nothing of Deephaven is patched. Deephaven has no `stop()` either: it registers how
to stop as shutdown tasks, and the framework's `stop()` runs those tasks and then ends the two
thread pools they leave behind (a standalone server exits right after, a host does not).

**F6. The Spring Boot fat jar breaks formula compilation.** `java -jar app.jar` failed on the
first `update` with `Error Compiling Formula Expression`: Deephaven compiles query formulas with
`javac` at run time and cannot see classes inside nested jars.

**F7. The extracted layout works.** `java -Djarmode=tools -jar app.jar extract` produces a thin
application jar with a `Class-Path` manifest and a `lib/` folder. Run that way, formulas compile.
An `application`-plugin distribution (start script plus `lib/`) works for the same reason.

**F8. Tomcat's WebSocket implementation collides with Jetty's.** With both on the classpath,
Deephaven's web context failed to start: `WsServerContainer cannot be cast to
JakartaWebSocketServerContainer`, and the IDE returned 503. Whether it happens depends on
classpath order, so it passed in one launch mode and failed in another. Excluding
`org.apache.tomcat.embed:tomcat-embed-websocket` fixes it in every mode. Spring MVC does not need
that module unless the host serves its own WebSockets.

**F9. Spring's dependency management must not override Deephaven's versions.** Spring Boot
3.5.16 manages Jetty 12.0.36 and Netty 4.1.135, older than what Deephaven 42.4 needs (Jetty
12.1.11, Netty 4.1.136). The spike did **not** apply the `io.spring.dependency-management` plugin; it imported
the Deephaven BOM as an enforced platform and Spring's BOM as a plain platform. Resolved result:
Jetty 12.1.11, Netty 4.1.136, gRPC 1.76.2, protobuf 4.33.2, Groovy 3.0.25 (Deephaven's), Tomcat
10.1.55, Jakarta Servlet 6.0.0. Spring Boot 4.1.2 manages Netty 4.2.19, Jetty 12.1.14, Tomcat 11 and
Servlet 6.1 and was not tried; it is an open item, not a default.

*Correction found while building phase P1.* A plain platform stops Spring from **lowering** a
version, but it still **raises** one wherever Spring manages a newer version than Deephaven uses.
The list above named only the libraries where Deephaven is ahead. With Spring Boot 3.5.16 next to
Deephaven 42.4, thirteen libraries on a Spring host's classpath resolved above the version
Deephaven alone resolves: Jackson (`jackson-core`, `jackson-databind`, `jackson-dataformat-yaml`,
`jackson-datatype-jdk8`, `jackson-datatype-jsr310` and `jackson-bom`, 2.21.2 to 2.21.4), Gson
(2.11.0 to 2.13.2), `commons-dbcp2` (2.11.0 to 2.13.0), `commons-pool2` (2.12.0 to 2.12.1),
`httpclient5` (5.5 to 5.5.2), `httpcore5` (5.3.5 to 5.3.6), `httpcore5-h2` (5.3.4 to 5.3.6) and
`jcl-over-slf4j` (1.7.30 to 2.0.18). So S5 ran Spring and Deephaven together on those newer
versions, and it worked. The build now goes the other way: a Spring host pins every library that
Deephaven brings to the version Deephaven alone resolves ([design section 3](01-design.md)).
Spring on those pinned versions has not been run yet; phase P5 is where it first is.

**F10. One Deephaven per JVM, and shared directories by default.** Configuration and the update
graph are process-wide singletons, so two instances mean two JVMs. Each instance also defaults to
the same `~/.cache/deephaven` (property `deephaven.cacheDir`, with `deephaven.configDir` and
`deephaven.dataDir`), which holds compiled formula classes. Two instances on one host need their
own directories.

## The embedding core, as it ran

```java
public final class EmbeddedComponentFactory extends ComponentFactoryBase<EmbeddedComponentFactory.EmbeddedComponent> {
    @Override
    public EmbeddedComponent build(Configuration configuration, PrintStream out, PrintStream err) {
        return DaggerEmbeddedComponentFactory_EmbeddedComponent.builder()
                .withOut(out).withErr(err)
                .withJettyConfig(JettyConfig.buildFromConfig(configuration).build())
                .build();
    }

    @Singleton
    @Component(modules = EmbeddedModule.class)
    public interface EmbeddedComponent extends JettyServerComponent {
        Provider<ScriptSession> scriptSession();        // our additions
        Provider<ExecutionContext> executionContext();

        @Component.Builder
        interface Builder extends JettyServerComponent.Builder<Builder, EmbeddedComponent> {}
    }

    @Module(includes = {JettyServerModule.class, FlightSqlModule.class, TicketResolversFromServiceLoader.class,
            ClientChannelFactoryModule.class, SslConfigModule.class, CommunityAuthorizationModule.class,
            CommunityDefaultsModule.class, AuthorizationProviderAdapterModule.class})
    public interface EmbeddedModule {
        @Provides @UserAgent
        static String userAgent() { return BarrageSessionFactoryConfig.userAgent(List.of("dh-embedded")); }
    }
}

// in the host
Configuration configuration = MainHelper.init(args, Host.class);
EmbeddedComponent component = new EmbeddedComponentFactory().build(configuration);
DeephavenApiServer server = component.getServer().run();          // Groovy app modules run in here
try (SafeCloseable ignored = component.executionContext().get().open()) {
    Table t = server.getUpdateGraph().sharedLock().computeLocked(() -> TableTools.timeTable("PT1S"));
    component.scriptSession().get().getQueryScope().putParam("java_table", t);
}
```

Dependencies that made it resolve and run:

```kotlin
configurations.all {
    exclude(group = "io.deephaven", module = "deephaven-extensions-kafka")           // F2
    exclude(group = "org.apache.tomcat.embed", module = "tomcat-embed-websocket")    // F8, Spring hosts
}
dependencies {
    implementation(enforcedPlatform("io.deephaven:deephaven-bom:42.4"))              // F9
    implementation("io.deephaven:deephaven-server-jetty")
    implementation("io.deephaven:deephaven-extensions-flight-sql")
    implementation("io.deephaven:deephaven-util-processenvironment")
    implementation("com.google.dagger:dagger:2.59.2")
    annotationProcessor("com.google.dagger:dagger-compiler:2.59.2")
    runtimeOnly("io.deephaven:deephaven-log-to-slf4j")
}
```

Start-up settings used: `-Ddeephaven.console.type=groovy`, `-Dhttp.port=<port>`,
`-Ddeephaven.application.dir=<dir>`, and for the spike only
`-DAuthHandlers=io.deephaven.auth.AnonymousAuthenticationHandler`.

## Not yet tested

- An authenticated subscription from B to A (the spike ran anonymous).
- What B's tables do when A restarts (reconnect behaviour).
- Subscribing to a blink table across instances, as opposed to an append-only one.
- Spring Boot 4.x, macOS and Windows, and running inside a container.
- Proxying the Deephaven UI through the Spring port (single origin).

Each is assigned to a phase in [02](02-implementation-phases.md).
