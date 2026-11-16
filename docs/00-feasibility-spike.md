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
Spring on those pinned versions first ran in phase P5: the application context, Spring MVC on
Tomcat, Jackson and the actuator all work, in the test's JVM and from the extracted layout. No
library needed Spring's version back.

**F10. One Deephaven per JVM, and shared directories by default.** Configuration and the update
graph are process-wide singletons, so two instances mean two JVMs. Each instance also defaults to
the same `~/.cache/deephaven` (property `deephaven.cacheDir`, with `deephaven.configDir` and
`deephaven.dataDir`), which holds compiled formula classes. Two instances on one host need their
own directories.

**F11. Deephaven 42.4 does not start on Windows as shipped.** Found by the first Windows CI run,
after phase P2. Its storage service (`FilesystemStorageServiceGrpcImpl`, the web IDE's saved
layouts and notebooks) creates `/layouts` and `/notebooks` below its storage directory at start,
and removes the leading `/` only if `/` is the platform's file separator. On Windows it is `\`,
so the path points outside the storage directory and the service fails with
`INVALID_ARGUMENT: Invalid path: /layouts` before the server is up. Everything else had passed
there: the build, the build-logic tests and the JVM-flag check.

The framework works around the start: on Windows it sets Deephaven's `storage.path.defaults` to
`layouts,notebooks`. That alone left the second half open: the web IDE sends the same
`/layouts/...` paths when it loads and saves layouts and notebooks, and the same code rejects
them.

*Closed in phase P7.* Our component now puts an interceptor, `StoragePaths`, in front of the
storage service. Where the file separator is not `/` it drops the leading slash from the paths
of a request, and gives a listing back with forward slashes, because the service builds its
answers from the platform's separator (`/layouts\main.json`) and the web IDE could not ask for
such a path again. On Linux and macOS it does nothing, so Deephaven's own code runs there
unchanged. `StorageServiceTest` asks a running instance the way the web IDE does, over the
network and with its paths: list, create, save, fetch, move, delete, and a path that leaves the
storage directory, which is still refused. It runs on all three systems, and on the Windows
runner it is the test that fails without the interceptor. **Still not seen:** the web IDE itself
on Windows, in a browser. The requests it sends are covered; that nothing else in it trips
over Windows is not.

**F12. A subscription by address cannot log in; a session of our own can.** Measured in phase
P4. Deephaven resolves `dh+plain://host:port/scope/<table>` through `BarrageTableResolver`,
which opens its session with an empty configuration; upstream's source says so in a TODO
(deephaven-core#3421). So the way the spike subscribed (S4) works only against an upstream with
anonymous access. A session made with `BarrageSessionFactoryConfig`, from the server's own
scheduler, allocator and channel factory, takes an authentication value, and with
`io.deephaven.authentication.psk.PskAuthenticationHandler <key>` it subscribes to a
key-protected upstream. `RemoteTables` therefore always makes its own session, with or without
a key. A wrong key and a missing key are both refused at once by the upstream
(`UNAUTHENTICATED`), in under two seconds, and are not retried.

**F13. A subscribed table does not survive its upstream going away.** Measured in phase P4,
with A as a second process. Within about half a second of A being killed, B's subscribed table
and every table derived from it are marked failed. They keep the rows they had, and can still be
read. When A is started again they stay failed: a Deephaven table cannot be pointed at a new
source, and nothing re-subscribes by itself. An orderly stop of A has the same effect as a kill.
So B does **not** recover on its own. What the framework does about it is in
[design section 4.1](01-design.md): it notices the failure, shows it in a status table, keeps
trying to reach the upstream, and then runs the application scripts again so that they subscribe
anew and rebuild what they derive.

Seen in a browser in phase P5: a table panel or an embedded table widget that was open when the
upstream went away does not simply freeze. It shows "Unable to open table" over its last rows,
and has to be opened again once the tables have been rebuilt.

**F14. Deephaven's own way of stopping its update graph can wait for ever.** Found in phase P5,
when a Spring host asked to stop did not. `PeriodicUpdateGraph.stop()` first ends the threads
that process a cycle's notifications and then waits for the cycle in flight to finish. A cycle
that is still waiting for those threads never finishes, so `stop()` never returns, and neither
does whoever called it: a Spring context being closed, a JVM handling Ctrl-C. The thread dump
showed the refresh thread holding the update graph's exclusive lock inside
`ConcurrentNotificationProcessor.doWork`, no notification threads left, and the shutdown hook
waiting for that lock. An instance with ticking tables has such work in most cycles, so this is
a matter of timing, not of a rare state: of the first three stops of the Spring host, one hung. A standalone Deephaven
never calls `stop()`, it exits the JVM, which is why the race is not seen there. The framework
now stops the graph while holding the exclusive lock, when no cycle is in flight
(`UpdateGraphStop`), and a test stops an instance in the middle of a deliberately slow cycle.
With Deephaven's `stop()` called directly that test does not finish.

**F15. Spring MVC serves Deephaven's web files too, from its own port.** Deephaven ships its web
UI as about five hundred files under `META-INF/resources` in `deephaven-web-42.4.jar`. That is
where a servlet container looks for static content in jars, so Spring Boot's Tomcat answers
`http://localhost:8081/ide/index.html` and `/jsapi/dh-core.js` with Deephaven's files. Nothing
is behind them there: the API is on Deephaven's port, and an IDE opened from the application's
port cannot connect. `/ide/` itself answers 404, so nobody arrives there by following a link.
The files are public and static, and the application's own static files take precedence. This is
left as it is and written down: removing it means replacing Spring Boot's static resource
handling, and the property that looks like the cure (`spring.web.resources.static-locations`)
is not one, because Tomcat adds the jars' resources by a second route. A host with no static
content of its own could set `spring.web.resources.add-mappings=false`; that was not tried here.

**F16. A JRE is not enough.** Found in phase P6, with the first container image. The plan named
a Temurin 21 JRE as the base image. On it the application does not start:
`No Java compiler provided - are you using a JRE instead of a JDK?`. Deephaven compiles query
formulas with `javac` while it runs (the reason behind F6 as well), and a JRE has no compiler.
The base image is therefore a JDK (`DHC_CONTAINER_BASE_IMAGE`), the same holds for a runtime
image made with `jlink` unless it includes `jdk.compiler`, and the preflight check now says so
before anything starts.

**F17. The path of an application script is limited, because Deephaven makes a file name of
it.** Found in phase P7, by following the README in a fresh clone that happened to sit in a
deep directory. Instance A did not start: `java.lang.RuntimeException: java.io.IOException:
File name too long`. Deephaven's Groovy session compiles each application script into a class
named after the script's **whole path**, with every character that is not a letter or a digit
turned into an underscore, and writes that class to a file of the same name plus a counter and
`.class`. A file name holds 255 characters on Linux, macOS and Windows alike. The script was
`<checkout>/build/demo/dev/dhc-a-java-work/app.d/dhc-a/10_tables.groovy`; with a checkout path
of 210 characters that is 269. Every earlier run had a shorter checkout path, and CI's are
short.

Two things in the framework answer it. Deephaven takes the application directory as it is
given, so the framework gives it **relative to the directory the JVM was started in** whenever
that is shorter, which it is for an application started from its checkout or its installation:
the same script is then `build/demo/dev/dhc-a-java-work/app.d/dhc-a/10_tables.groovy`, 59
characters wherever the checkout is. And a script whose path is still longer than 215
characters is refused before Deephaven starts, with its length and what to change; the 40
characters kept back are for the counter, which grows each time the scripts run again after a
reconnect, and for the names of closures in the script. `scripts/run.sh` now starts a plain
Java host from the repository root, wherever it was called from.

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

- ~~An authenticated subscription from B to A~~: done in P4, finding F12.
- ~~What B's tables do when A restarts~~: done in P4, finding F13.
- ~~Spring on the library versions pinned to Deephaven's~~: done in P5, see F9.
- Subscribing to a blink table across instances, as opposed to an append-only one.
- ~~Running inside a container~~: done in P6, finding F16.
- ~~A macOS runner in CI~~: done in P7.
- Spring Boot 4.x (decision D2).
- Proxying the Deephaven UI through the Spring port (single origin).
- TLS between the instances: the `dh://` scheme is accepted, and nothing has run over it.
- The web IDE on Windows, in a browser (F11).

The four that are open are the stretch items of [phase P7](02-implementation-phases.md#p7-hardening-and-documentation)
and were left out of it; the blink-table subscription was never assigned to a phase.
