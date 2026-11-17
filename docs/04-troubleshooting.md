# 04. Troubleshooting

Ordered by what you see. Each entry is the symptom, its cause and the fix. Where the text of a
message is quoted, it is the text the build, the framework or the scripts print, so searching
this page for a few words of the message finds the entry. Findings "F*n*" are in
[00](00-feasibility-spike.md), the day-to-day commands in [03, the runbook](03-runbook.md).

Most of these cannot happen to an application built with the convention plugins and started by
the scripts: the plugins and the preflight checks exist because each one did happen once. They
come back when a host is built or started another way: a new module without the plugin, a
hand-written `java` command, an IDE run configuration, another base image.

- [The build fails](#the-build-fails)
- [The application does not start](#the-application-does-not-start)
- [It runs, and something is wrong](#it-runs-and-something-is-wrong)
- [Instance B and its upstream](#instance-b-and-its-upstream)
- [It does not stop](#it-does-not-stop)
- [The scripts](#the-scripts)
- [Containers](#containers)
- [Windows](#windows)
- [When none of this fits](#when-none-of-this-fits)

## The build fails

| Symptom | Cause | Fix |
|---|---|---|
| `Could not find io.confluent:...` or `org.apache.kafka:kafka-clients:...-ccs` | F2. Deephaven's server depends on its Kafka extension, whose libraries are not on Maven Central. | Build the module with a `dhc.*` convention plugin: every one of them excludes `io.deephaven:deephaven-extensions-kafka`. To keep Kafka instead, mirror the Confluent repository (decision D4). |
| `Could not find io.deephaven:deephaven-server-jetty-app` | F1. Upstream's ready-made server module is not published for 42.4. | Do not depend on it. `framework/embed-core` declares the component it would have provided. |
| `prefer settings repositories over project repositories` | A module declares a repository of its own. | Remove it. Every repository comes from `config/repos/repos.env` through the `dhc.repositories` settings plugin ([config/README.md](../config/README.md#artifact-sources)). |
| `dhc.repositories: ... but the Gradle property dhcRepoUsername is not set` | `repos.env` says the mirror needs a login and none was given. | Put `dhcRepoUsername` and `dhcRepoPassword` in `~/.gradle/gradle.properties`, never in the repository. |
| `dhc.repositories: ... has the unknown key ...` | A typo in `repos.env` or `repos.local.env`. | The message lists the known keys. |
| `gradle/wrapper/gradle-wrapper.properties downloads Gradle from ... but config/repos/repos.env ... ask for ...` | The Gradle download address was changed in `repos.env` and not in the wrapper, which keeps its own copy. | `scripts/sync-gradle-wrapper.sh`. |
| `verifyDeephavenAlignment: N of M libraries on the runtime classpath of ... resolve to a different version than Deephaven alone resolves them to` | F9. A dependency, a forced version or another BOM moved a library that Deephaven brings. | Run the `dependencyInsight` command the message ends with, and remove what moved the version. Every library compared is listed in the application's `build/reports/deephaven-alignment/report.txt`. |
| `... applies io.spring.dependency-management. It must not` | F9. That plugin forces Spring's versions over Deephaven's. | Remove the plugin from the module. `dhc.embedded-spring-app` already imports Spring's BOM, as a plain platform. |
| `verifyDeephavenAlignment: ... has no io.deephaven module on its runtime classpath` | An application module with no Deephaven in it. | An embedded application depends on `:framework:embed-core` or `:framework:embed-spring`. |
| `validateConfig found N problem(s)` | A settings file breaks a rule: an unknown key, a key in one environment and not in another, something that looks like a secret, quotes around a value. | Each line of the message names the file, the line and the rule ([config/README.md](../config/README.md#application-settings)). |
| `checkDocs found N broken link(s)` | A document links to a file or a heading that is not there, usually after a rename. | Each line names the document and line. For a heading the message lists the headings the target has. |
| `cannot find symbol ... DaggerEmbeddedComponentFactory_EmbeddedComponent`, or errors about a Deephaven `...Module` class | F1. Dagger did not generate the component, almost always because a Deephaven upgrade renamed or moved one of the modules it is made of. | [05, upgrade checklist](05-upgrade-checklist.md). All of those names are in one class, `EmbeddedComponentFactory`. |
| a test task ends after twenty minutes with a timeout | Every test task has that limit, so that a hang fails the build instead of stopping it. | Take a thread dump of the test JVM before the limit (`jstack <pid>`); see [it does not stop](#it-does-not-stop). |

## The application does not start

In a Spring host these arrive as `The embedded Deephaven did not start: ...`, under
`APPLICATION FAILED TO START`. The text after the colon is what is listed here.

| Symptom | Cause | Fix |
|---|---|---|
| `This JVM was started without N of the 3 flags Deephaven needs` | F3. Deephaven needs three `--add-exports` and `--add-opens` flags, and they cannot be set from inside the JVM. | The message prints all three. `run`, `bootRun`, the tests, the start scripts and the container entry point pass them already; add them to a hand-written `java` command or an IDE run configuration. For a Spring host they are also in `build/extracted/jvm.args`: `java @jvm.args -jar <app>.jar`. |
| `This Java (...) has no compiler: it is a JRE` (from Deephaven itself: `No Java compiler provided - are you using a JRE instead of a JDK?`) | F16. Deephaven compiles query formulas with `javac` while it runs. | Start it with a JDK 21. In a container, the base image has to be a JDK (`DHC_CONTAINER_BASE_IMAGE`); a `jlink` image has to include `jdk.compiler`. |
| `Deephaven cannot run from inside a fat jar` (without the check: `Error Compiling Formula Expression` on the first formula) | F6. `javac` cannot read classes in a jar within a jar. | Run the extracted layout: `./gradlew :apps:<app>:extractBootJar`, then `scripts/run.sh <app>`. Never `java -jar` on the `bootJar`. |
| `Port N on <host> cannot be used` | Something listens there, most often an earlier run of the same application. | `scripts/demo.sh status` and `down`; or set `DHC_HTTP_PORT` to a free port. `0` picks one. |
| `The work directory ... is not writable`, `Cannot create the work directory` | `DHC_WORK_DIR` points where this process may not write. | Point it at a directory of its own. In a container that is `/var/dhc/work`, owned by the user `dhc`. |
| `The application script ... has a path of N characters, and 215 is the longest Deephaven can run` (without the check: `java.io.IOException: File name too long`) | F17. Deephaven names the class file of a script after the script's whole path, and a file name holds 255 characters. A checkout, installation or work directory deep in the file system gets there. | Start the application from its checkout or installation directory, as the scripts do: the path is taken relative to the directory the JVM starts in when that is shorter. Or point `DHC_WORK_DIR` at a directory with a short path. |
| `Application module 'x' is not on the classpath` | `DHC_APP_MODULES` names a module the host does not depend on, or misspells it. | Add the module's project to the host's dependencies, or correct the setting. |
| `DHC_HTTP_PORT must be a port number`, `DHC_AUTH_MODE must be psk or anonymous`, `... must be a whole number` | A setting has a value of the wrong kind. | The message names the setting. Where it came from: [changing a setting](03-runbook.md#changing-a-setting). |
| `WsServerContainer cannot be cast to JakartaWebSocketServerContainer`, and the web IDE answers 503 | F8. Tomcat's WebSocket implementation is on the classpath next to Jetty's, usually through `spring-boot-starter-websocket`. Whether it bites depends on classpath order, so it can pass from the IDE and fail when packaged. | Keep `org.apache.tomcat.embed:tomcat-embed-websocket` excluded, as `dhc.embedded-spring-app` does. A host that needs WebSockets of its own cannot serve them from Tomcat in the same JVM. |
| `An embedded Deephaven was already started in this JVM`, `This instance was already started` | F10. Deephaven's configuration and update graph belong to the process: one instance per JVM, and no restart. | A second instance is a second process. In tests, every test class gets a JVM of its own. |
| `Upstream dhc-a at ... did not answer within 60 seconds` | Instance B waits for A at start, and A is not there. | Start A first, or correct `DHC_UPSTREAM_DHC_A_URL`. The message ends with the last error. |
| `Upstream dhc-a at ... refused the login` | F12. The key B presents is not A's key, or A asks for one and B has none. | `DHC_UPSTREAM_DHC_A_PSK` has to equal A's `DHC_AUTH_PSK`. This is not retried. |
| `DHC_UPSTREAM_DHC_A_URL is not set` | A host that names an upstream needs its address. | `dh+plain://host:port`, or `dh://host:port` for TLS. |
| It starts, and prints a key nobody chose | No `DHC_AUTH_PSK` in an environment that asks for keys, so Deephaven makes one up. That is the safe default, and what an image does when `DHC_ENV` is not set. | Give the key in the environment (`DHC_DHC_A_AUTH_PSK`, `DHC_DHC_B_AUTH_PSK`), or run `dev`. |
| `... runs WITHOUT AUTHENTICATION` in the log | `DHC_AUTH_MODE=anonymous`, which `dev` sets. Anybody who reaches the port can run code in the JVM. | Expected in `dev`, on `localhost`. Anywhere else, set `DHC_AUTH_MODE=psk`. |

## It runs, and something is wrong

| Symptom | Cause | Fix |
|---|---|---|
| `May not initiate serial table operations for update graph DEFAULT` | F4. A host thread touched a table without the update graph's lock and an execution context. | Do table work inside `engine().call(...)` or `engine().run(...)`. |
| A request to the host hangs, or the whole application stops answering, around the time the upstream comes back | Host code waited for another lock, or did slow work, inside `engine().call`. That holds the update graph's shared lock, which the reconnect needs (found in P5). | Inside `engine()` do table work only. Read other state before or after. |
| `Error Compiling Formula Expression` although nothing runs from a fat jar | The formula names a class or method the compiler cannot reach: the class is not `public`, or not on the classpath. | Make the class and method `public`. Formulas are compiled outside your package. |
| The JVM exits with `Initiating shutdown due to: Uncaught exception in thread ...` | F5. Under Deephaven's own policy an uncaught exception in any thread ends the process. That is the default for a plain Java host. | A host that should survive uses `SideEffectPolicy.hostKeepsControl()`, as the Spring hosts do. |
| `System.out` output also appears in the web IDE's log, or is formatted differently | F5. Deephaven replaces the standard streams, under the same policy. | `replaceStandardStreams` in `SideEffectPolicy`. |
| The Deephaven IDE opened from the application's port (`http://localhost:8081/ide/index.html`) loads and cannot connect | F15. Spring MVC serves Deephaven's static web files too; the API is only on Deephaven's port. | Open the IDE on Deephaven's port: `http://localhost:10001/ide/`. |
| The web IDE opens with empty panels, and the log has `NOT_FOUND: Could not resolve ... variable 'x' not found` | The browser keeps the last layout of each address. Another instance, or an earlier version, ran on the same host and port and had other tables. | Close the empty panels. Nothing is wrong with the instance. |
| The heap is not what `DHC_JVM_HEAP` says | The heap cannot be set from inside the JVM. Only `scripts/run.sh`, `scripts/demo.sh` and the container entry point read that setting. | Start through one of them, or pass `-Xmx` yourself. |
| Two instances on one machine disturb each other, with odd formula errors | F10. They share a directory, by default `~/.cache/deephaven`, which holds compiled formulas. | Give each its own `DHC_WORK_DIR`. The scripts and the framework's default do; a `DHC_WORK_DIR` set once for a whole shell does not. |
| A table shows fewer rows than expected, and `execs` stops growing at 10,000 | `execs` is a ring table of the last `DHC_RING_CAPACITY` executions, and `exec_events` holds one update cycle only. | That is their purpose. `orders` has every order. |

## Instance B and its upstream

| Symptom | Cause | Fix |
|---|---|---|
| B's tables stop moving; `feed_status` says `DISCONNECTED`; `dhc-a` is `DOWN` in `/actuator/health`; readiness answers 503; `/api/summary` says `"stale":true` | F13. A is away. A subscribed table does not survive that and does not come back by itself, so the framework watches and reconnects. | Bring A back. B needs nothing: [when A goes away](03-runbook.md#when-a-goes-away). Do not restart B for it. |
| A table open in a browser shows "Unable to open table" over its last rows | F13. After a reconnect the name refers to a new table; the one the browser held is finished. | Open the table again. |
| After A came back, B's totals are lower than before | A is in-memory. A restarted A starts its book again, and B shows what A has. | Expected. Persistence is out of scope. |
| Host code holds a `Table` that never changes again after an outage | The same: the object is the table from before. | Look tables up by name each time: `deephaven.table("orders_a")`. |
| `Reconnected to upstream ... but rebuilding the tables failed; trying again` | An application script cannot run a second time, for example because it assumes a variable does not exist yet. | A script that uses `host.remote` has to be a list of definitions that can run again. |
| A script that subscribes by address, `dh+plain://host:port/scope/table`, gets `UNAUTHENTICATED` | F12. Deephaven's resolver for such addresses cannot log in. It works only against an anonymous upstream. | Use `host.remote("dhc-a").subscribe("table")`, which makes a session of its own, with the key. |
| `scripts/smoke.sh`: `B's totals are not between two readings of A's` | B is not following the A that was read: it is disconnected, or it points at another environment's A. | `scripts/demo.sh status`, then `feed_status` on B and `DHC_UPSTREAM_DHC_A_URL`. |

## It does not stop

| Symptom | Cause | Fix |
|---|---|---|
| A stop takes ten seconds and logs a `TimeoutException` from `JettyBackedGrpcServer.stopWithTimeout` | Browsers were connected. Deephaven waits for them up to `shutdown.timeoutMs` and then goes on. | Harmless. `-Dshutdown.timeoutMs=<ms>` shortens the wait. |
| `Deephaven's refresh thread ... did not end within 5000 ms of the update graph being stopped` | The thread was in a long cycle. It is a daemon thread. | Harmless; it does not keep the JVM alive. |
| Ctrl-C, or closing the Spring context, never returns; a thread dump shows a thread in `PeriodicUpdateGraph.stop` and the refresh thread inside `ConcurrentNotificationProcessor.doWork` | F14. Deephaven's own way of stopping its update graph can wait for ever. | Stop through `EmbeddedDeephaven.stop()` (or `close()`), which stops the graph under its exclusive lock. Never call Deephaven's `stop()` on the update graph directly. |
| After `stop()`, three Deephaven-related daemon threads are still there | Deephaven's reference cleaner, JNA's cleaner and the JDK's process reaper. Nothing can end them. | Expected. None keeps the JVM alive. |
| Instance A takes ten seconds to stop while B is connected | An instance that subscribes is a client too, and Deephaven waits for its clients. | Expected. B is told only when A is gone, or at the start of the wait; both were seen. |
| `demo: <app> did not stop within 40 seconds and was ended` | The application did not stop when asked. | It is gone now. If it repeats, take a thread dump before `demo.sh down` and look for the entry above. |

## The scripts

| Symptom | Cause | Fix |
|---|---|---|
| `something already answers at <url>. Stop it first` | An application from another run is on that port. `demo.sh` will not start next to it, because it could not tell the two apart. | `scripts/demo.sh status` and `down`, for the environment it was started in (`DHC_ENV`). If the scripts did not start it, find the process that holds the port. |
| `<app> is not built. Run: ./gradlew ...` | The scripts start what Gradle built and build nothing. | Run the command in the message, or `./gradlew installDist extractBootJar` for all. |
| `environment 'uat' asks for keys. Set DHC_...` | `uat` and `prod` have no keys in their files, on purpose. | Export the variable the message names ([config/README.md](../config/README.md#keys)). |
| `<app> exited before <url> answered` | The application failed at start. The last lines of its log follow the message. | Find the message under [the application does not start](#the-application-does-not-start). |
| `<app> did not answer at <url> within 180 seconds` | A slow machine, or an application that waits: B waiting for an A that is not there. | `scripts/demo.sh logs <app>`. `DHC_DEMO_START_TIMEOUT` gives it longer. |
| `smoke: the probe could not read table ...` in an environment with keys | The smoke script logs in like any client, and has no key. | Export the same three keys for `smoke.sh` as for `demo.sh`. |
| `the probe is not built` | The probe is an application too. | `./gradlew :apps:dhc-probe:installDist`. |
| `scripts/check_env.sh`: `MISSING  Java 21 or later; java is version ...` | The scripts use the `java` of `JAVA_HOME` or the `PATH`. Gradle finds its own; the scripts do not. | Put a JDK 21 in `JAVA_HOME`. |

## Containers

| Symptom | Cause | Fix |
|---|---|---|
| The image build stops with `The base image needs bash and curl.` | The entry point is a bash script and the health check uses curl. | Use a base image that has both, or add them in a base image of your own. |
| `groupadd: not found` or `useradd: not found` during the build | The base image creates users another way. | Change those two lines of the Dockerfile for your base image. |
| The container exits at once with `This Java (...) has no compiler` | F16. The base image is a JRE. | `DHC_CONTAINER_BASE_IMAGE` has to be a JDK 21. |
| With Podman on macOS a hand-written `podman build` fails at `COPY` with `cannot allocate memory` | Podman's virtual machine cannot list a directory of several hundred jars. | `./gradlew buildImages`, which sends the build context as one archive. |
| `podman ps` shows no health for the container | Podman keeps a `HEALTHCHECK` only in docker's image format. | `./gradlew buildImages` asks for that format. By hand: `podman build --format docker`. |
| A container started by hand asks for a key nobody set | An image sets no `DHC_ENV` and so runs key-protected, with a key it makes up and prints. | Pass `DHC_ENV`, or the keys. The compose file sets `dev`. |
| The ports cannot be reached from another machine | The compose file publishes them on `127.0.0.1` only, because `dev` lets anybody in. | Deliberate. Change the published address only together with `DHC_ENV` and keys. |
| Instance B stays `healthy` while A is stopped | The health check is the liveness question and leaves the upstream out. | Deliberate: restarting B does not bring A back. Ask `/actuator/health/readiness` for routing. |

## Windows

| Symptom | Cause | Fix |
|---|---|---|
| `INVALID_ARGUMENT: Invalid path: /layouts` at start, and the server never comes up | F11. Deephaven 42.4's storage service does not understand its own paths on Windows. | The framework sets `storage.path.defaults` on Windows. If you set that property yourself, write `layouts,notebooks`, without slashes. |
| The web IDE cannot save or load layouts and notebooks, with `Invalid path` in the log | F11 again, for the requests the web IDE sends while it runs. | The framework's component puts `StoragePaths` in front of the storage service. A host that builds its own Dagger component has to add it too. |
| `scripts/demo.sh: command not found`, or the script opens in an editor | Windows does not run shell scripts by name. | `bash scripts/demo.sh up`, in Git Bash. |
| A hand-written `java -cp ...` with every jar, or a start script of your own, is refused as too long | An embedded Deephaven has close to three hundred jars. Listed one by one they pass the 8,191 characters `cmd` accepts on a line, and the 32,767 a Windows process may have once the install path is long. | Start a plain Java host through its `installDist` start script, which names one jar whose manifest lists the others. In a test or tool of your own, use a Java argument file (`java @file`). |
| `demo.sh down` says an application was ended, not stopped | Windows cannot ask a process to stop. | Expected. See [stopping](03-runbook.md#stopping). |
| A script fails on a line that looks correct, with `\r` in the message | A checkout with Windows line endings. `.gitattributes` asks for LF. | `git add --renormalize .`, or clone again without overriding `.gitattributes`. |

## When none of this fits

1. `scripts/check_env.sh`, then `scripts/demo.sh status` and `scripts/demo.sh logs`.
2. The first `ERROR` or `Caused by` in the log, not the last. Deephaven logs the consequences
   of a failure at length after the cause.
3. A thread dump for anything that hangs: `jstack <pid>`, with the pid from
   `build/demo/<environment>/<app>.pid` on Linux and macOS.
4. The same application alone, with defaults: `scripts/check-start.sh dhc-a-java`.
5. Whether it is ours or Deephaven's: the findings in [00](00-feasibility-spike.md) say what
   Deephaven does by itself, and [design section 9](01-design.md#9-risks-and-how-the-plan-answers-them)
   lists what the build and the tests guard against.
