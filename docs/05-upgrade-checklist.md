# 05. Upgrade checklist: moving off Deephaven 42.4

Embedding uses parts of Deephaven that are public but not promised: Dagger modules, start-up
classes, configuration property names, one thread name. They can change in any release. This
page says where each of them is in this repository, in what order to change things, and what
to run to know the new version works. Findings "F*n*" are in [00](00-feasibility-spike.md).

The same order serves for a Spring Boot or a Java upgrade; the sections that are only about
Deephaven say so.

## 1. Change the version, in one place

The Deephaven version is written once: `deephaven` in
[gradle/libs.versions.toml](../gradle/libs.versions.toml). Every `io.deephaven` library takes
its version from the enforced `deephaven-bom`.

| Also in that file | What to do with it |
|---|---|
| `dagger` | Set it to the version the new Deephaven itself resolves: `./gradlew :framework:embed-core:dependencyInsight --configuration runtimeClasspath --dependency com.google.dagger:dagger`. The annotation processor has to match the runtime. |
| `spring-boot` | Leave it for a Deephaven upgrade. Nothing else has to be edited for Spring: `dhc.embedded-spring-app` works out, at build time, every library Deephaven brings and pins it to the version Deephaven alone resolves (F9). |
| `gradle` | Leave it. If it does change, run `scripts/sync-gradle-wrapper.sh` afterwards. |

Then check the two things about Java: which Java the new Deephaven needs (the toolchain is in
`dhc.java-conventions`, the base image in `config/repos/repos.env`), and whether its launcher
still passes the same three JVM flags (step 3).

## 2. Make it compile: the one class with the module list

```bash
./gradlew :framework:embed-core:compileJava
```

**[EmbeddedComponentFactory](../framework/embed-core/src/main/java/io/github/cmh/dhc/embed/EmbeddedComponentFactory.java)**
is the only class that names Deephaven's Dagger modules (F1). It lists eight:
`JettyServerModule`, `FlightSqlModule`, `TicketResolversFromServiceLoader`,
`ClientChannelFactoryModule`, `SslConfigModule`, `CommunityAuthorizationModule`,
`CommunityDefaultsModule`, `AuthorizationProviderAdapterModule`. When an upgrade renames, moves,
splits or adds one, this class stops compiling, or Dagger reports a binding nobody provides.

- Compare the list with what upstream's own server uses. In the Deephaven source that is the
  component in `server/jetty-app` (`CommunityComponentFactory`); its `@Module(includes = ...)`
  is the list to copy.
- A new required binding shows as a Dagger error that names the type. Upstream provides it in
  one of its modules; add that module here.
- First look at whether `io.deephaven:deephaven-server-jetty-app` is published now. If it is,
  this class may be replaceable by upstream's factory. Keep the three things ours adds: the
  host bindings application, the provisions the framework reads from the component (script
  session, execution context, scheduler, allocator, channel factory), and the storage
  interceptor.

## 3. Go through what else touches Deephaven's insides

All of it is in `framework/embed-core`, package `io.github.cmh.dhc.embed`, unless a row says
otherwise. For each row: does the class still compile, and does what it relies on still hold?

| Where | Relies on | Finding | If it changed |
|---|---|---|---|
| `EmbeddedDeephaven` (start) | `MainHelper.init`; the properties `deephaven.console.type`, `http.host`, `http.port`, `deephaven.application.dir`, `deephaven.cacheDir`, `deephaven.configDir`, `deephaven.dataDir`, `AuthHandlers`, `authentication.psk` | F5, F10 | Rename the property here. A property that is silently ignored shows as the wrong port, a shared cache directory or a made-up key. |
| `EmbeddedDeephaven` (stop) | Deephaven registers how to stop as shutdown tasks; `stop()` runs them and then ends two thread pools | F5 | `StartStopTest` and `ContextStopTest` fail on threads left behind. |
| `EmbeddedProcessEnvironment` | the property `default.processEnvironmentFactory`, and the process environment's interface | F5 | The host loses control: an uncaught exception exits the JVM. `StarterTest` checks the policy. |
| `UpdateGraphStop` | the refresh thread is named `PeriodicUpdateGraph.<name>.refreshThread`, and `PeriodicUpdateGraph.stop()` can wait for ever | F14 | Read upstream's `stop()` first: if it no longer waits for a cycle that cannot finish, this class can go. If the thread was renamed, correct the name. `StopUnderLoadTest` is the judge either way. |
| `AppModules` (`shortest`, `checkScriptPaths`) | `GroovyDeephavenSession` names a script's class file after the path it was given, and `ApplicationFactory` resolves a script against the application directory as given, relative or not | F17 | If scripts are named another way, the limit of 215 characters can go. If the directory is made absolute, the relative form stops helping and only the check is left. Its tests are in `AppModulesTest`; a start from a deep directory is the proof. |
| `StoragePaths`, and `storagePathDefaults` in `EmbeddedDeephaven` | `FilesystemStorageServiceGrpcImpl` drops a leading `/` only where it is the file separator | F11 | If upstream fixed it, both can go; `StorageServiceTest` on Windows says whether they still are needed, by passing without them. |
| `RemoteTables` | `BarrageSessionFactoryConfig`; the handler name `io.deephaven.authentication.psk.PskAuthenticationHandler`; a failed subscription stays failed | F12, F13 | If upstream's resolver can log in now (deephaven-core#3421), subscribing by address works with a key too; ours still does. If tables started to recover by themselves, the reconnect would rebuild them twice: read `UpstreamRestartTest`. |
| `Preflight`, and `DeephavenRules.mandatoryJvmFlags` in `build-logic` | the three JVM flags of upstream's launcher | F3 | The flags are written in those two places, on purpose: one applies them, one checks their effect. Change both. `MissingJvmFlagsTest` starts a JVM without them. |
| `Preflight` | formulas are compiled with `javac` at run time | F6, F7, F16 | Still true as long as Deephaven compiles formulas. `FatJarRefusedTest` and `ExtractedLayoutTest`. |
| `dhc.deephaven-library` in `build-logic` | the server depends on `deephaven-extensions-kafka`, which needs the Confluent repository | F2 | Remove the exclusion and run `./gradlew check`: if everything resolves from Maven Central, the exclusion is no longer needed. |
| `dhc.embedded-spring-app` in `build-logic` | Jetty's WebSocket implementation clashes with Tomcat's | F8 | Keep the exclusion unless `ExtractedLayoutTest` passes without it, in every launch mode. |
| `apps/dhc-b-spring`, `static/index.html` | the address of an embedded table: `/iframe/widget/?name=<table>`, and `?psk=` | P5 | The page shows empty frames. Open it in a browser (step 5). |
| `apps/*-module`, the Groovy scripts | Deephaven's Groovy version and table API: `lastBy`, `sumBy`, `RingTableTools` | P3, P4 | `validateAppModule` at build time, `RoleATablesTest` and `SubscribeToATest` at run time. |
| `framework/embed-testkit`, `Probe` | the Java client: `FlightSessionFactoryConfig`, `ScopeId` | P4 | `ProbeTest`, and every `scripts/smoke.sh`. |

## 4. Run everything

```bash
./gradlew check integrationTest
./gradlew installDist extractBootJar
scripts/check_env.sh
scripts/demo.sh up           && scripts/smoke.sh && scripts/demo.sh down
scripts/demo.sh up secondary && scripts/smoke.sh && scripts/demo.sh down
./gradlew buildImages
docker compose -f docker/compose.yaml up -d && scripts/smoke.sh && docker compose -f docker/compose.yaml down
```

What each part proves about the new version:

| Run | Proves |
|---|---|
| `verifyDeephavenAlignment` (in `check`, per application) | no library Deephaven brings resolves to another version in a host (F9). After an upgrade the pins of a Spring host move by themselves; a failure here means a version is forced somewhere by hand. |
| `ConventionPluginsTest` (in `check`) | the build rules: no Kafka (F2), no Tomcat WebSocket (F8), the JVM flags on every way to start (F3), the extracted layout |
| `StopUnderLoadTest`, `StartStopTest`, `ContextStopTest` | an instance stops when asked, in mid-cycle too, and leaves no threads (F5, F14) |
| `StorageServiceTest` | the web IDE's layouts and notebooks, with the web IDE's paths; on Windows, F11 |
| `RoleATablesTest` | role A's tables equal the oracle, row for row |
| `integrationTest` of `dhc-b-module` | two processes: B equals the oracle through a subscription; with a key, a wrong key, no key (F12); A killed and restarted (F13) |
| `integrationTest` of `dhc-b-spring` | Spring and Deephaven in one JVM; the extracted layout as a process (F7); the fat jar refused (F6); liveness and readiness while A is away |
| `demo.sh` and `smoke.sh`, both pairs | the applications as shipped, started by their start scripts, in all four combinations |
| `buildImages` and compose | the same inside containers, on the JDK base image (F16) |

Then let CI run it on Linux, macOS and Windows. Windows is where F11 and the command-line
limits live, and it is the only place they are tested.

## 5. Look at it once, in a browser

Tests do not open the web UI. After an upgrade, with `scripts/demo.sh up`:

1. `http://localhost:10000/ide/` opens, lists A's tables, and `orders` ticks.
2. `http://localhost:8081/` shows B's numbers and both embedded tables.
3. Stop A with `scripts/demo.sh down dhc-a-java` and start it again with `scripts/demo.sh up`:
   B's page says the feed is disconnected and then recovers by itself.
4. In either IDE, change the layout and reload the page: the layout is kept. On Windows this is
   the part of F11 that only a browser shows.

## 6. Write down what changed

- A finding that no longer holds: strike it through in [00](00-feasibility-spike.md) with the
  version that ended it, and remove the workaround and its test together.
- A new one: add it there as the next F-number, with what was measured, then the entry in
  [04, troubleshooting](04-troubleshooting.md) under the symptom it shows.
- The version numbers in the [README](../README.md) and at the top of
  [00](00-feasibility-spike.md) and [01](01-design.md).

## Upgrading Spring Boot instead

Within 3.5.x, change `spring-boot` in `gradle/libs.versions.toml` and run step 4. The pins
follow Deephaven, not Spring, so a patch release of Spring cannot move a Deephaven library.

Spring Boot 4.x is a different matter and has not been tried (decision D2). It manages Netty
4.2, Jetty 12.1, Tomcat 11 and Servlet 6.1, where Deephaven 42.4 is built on Netty 4.1 and
Servlet 6.0. Expect `verifyDeephavenAlignment` to hold the Deephaven side and the question to
become whether Spring 4 runs on the pinned versions: `StarterTest`, `DhcBSpringAppTest` and
`ExtractedLayoutTest` answer that.
