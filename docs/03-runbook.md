# 03. Runbook

How to run the applications of this repository, what "well" looks like, and what to do in the
situations that come up. When something fails with a message, go to
[04, troubleshooting](04-troubleshooting.md), which is ordered by symptom. Findings "F*n*" are
in [00](00-feasibility-spike.md), design sections in [01](01-design.md).

## What runs

Two instances, each a JVM of its own with one embedded Deephaven (F10). Each exists in two
kinds of host, so there are four applications, and a pair is one A with one B.

| Application | Role | Host | Deephaven port (`dev`) | Its own port (`dev`) |
|---|---|---|---|---|
| `dhc-a-java` | A: mock order executions | plain Java | 10000 | none |
| `dhc-a-spring` | A | Spring Boot | 10000 | 8080 |
| `dhc-b-java` | B: subscribes to A | plain Java | 10001 | none |
| `dhc-b-spring` | B | Spring Boot | 10001 | 8081 |

The primary pair is `dhc-a-java` with `dhc-b-spring`; the secondary pair is `dhc-a-spring` with
`dhc-b-java`. A Spring host has two ports: Deephaven's own server, and Spring MVC for the
application's REST API, health and page.

| Environment | Who may log in | Listens on | A, B (Deephaven) | A, B (Spring) | Heap |
|---|---|---|---|---|---|
| `dev` | anybody | `localhost` | 10000, 10001 | 8080, 8081 | 1g |
| `uat` | holders of the key | `localhost` | 11000, 11001 | 9080, 9081 | 2g |
| `prod` | holders of the key | every interface | 10000, 10001 | 8080, 8081 | 4g |

`dev` and `uat` have ports of their own and run side by side on one machine. `prod.env` names an
example host for A (`dhc-a.example.internal`); put the real one there before it is used.

## Before the first start

```bash
./gradlew installDist extractBootJar    # every application as it is shipped, and the probe
scripts/check_env.sh                    # what this machine has and lacks
```

`check_env.sh` changes nothing. `MISSING` in capitals is required (a Java 21, curl, the
configuration); `missing` is needed for part of the work only (a container runtime, an
application not built yet). A note that something already answers on a port means an earlier
run is still up: `scripts/demo.sh status`, then `scripts/demo.sh down`.

The machine needs a **JDK** 21, not a JRE (F16), on the `PATH` or in `JAVA_HOME`. On Windows,
run every script as `bash scripts/<name>` in Git Bash.

## Start, check, stop

| To | Run |
|---|---|
| start a pair in the background | `scripts/demo.sh up` (or `up secondary`) |
| see whether both run and answer | `scripts/demo.sh status` |
| check that B holds what A has | `scripts/smoke.sh` |
| read the last lines of the logs | `scripts/demo.sh logs` (or `logs dhc-b-spring`) |
| stop the pair, B first | `scripts/demo.sh down` |
| stop one of the two and leave the other running | `scripts/demo.sh down dhc-a-java`; `scripts/demo.sh up` starts it again |
| run one application in the foreground | `scripts/run.sh dhc-b-spring` (Ctrl-C stops it) |
| run one application from Gradle, for development | `./gradlew :apps:dhc-a-java:run`, `./gradlew :apps:dhc-b-spring:bootRun` |
| run a pair from container images | `./gradlew buildImages`, then `docker compose -f docker/compose.yaml up -d` |

Start A before B. B waits for A at start, for up to `DHC_UPSTREAM_CONNECT_TIMEOUT_SECONDS`
(60), and then gives up with a message that names A's address. `demo.sh up` does the order for
you, and refuses to start if something already answers on a port it is about to use.

Another environment: set `DHC_ENV`, and in an environment with keys the three keys first.

```bash
export DHC_DHC_A_AUTH_PSK=...  DHC_DHC_B_AUTH_PSK=...  DHC_UPSTREAM_DHC_A_PSK=...
DHC_ENV=uat scripts/demo.sh up
DHC_ENV=uat scripts/smoke.sh          # needs the same keys in its environment
DHC_ENV=uat scripts/demo.sh down
```

`DHC_UPSTREAM_DHC_A_PSK` is A's key again, for B to present. The keys come from wherever the
environment keeps its secrets; they are never in a file of this repository
([config/README.md](../config/README.md#keys)).

## What "well" looks like

| Ask | A healthy answer |
|---|---|
| `scripts/demo.sh status` | both applications `running`, each `answers at` its address |
| `scripts/smoke.sh` | ends with `smoke: passed` |
| `http://localhost:10000/ide/` | A's web IDE, with the tables `exec_events`, `execs`, `orders`, `order_cache`, `cache_stats` |
| `http://localhost:10001/ide/` | B's web IDE, with `execs_a`, `orders_a`, `cum_by_symbol`, `exec_rate`, `open_orders`, `feed_status` |
| the table `feed_status` on B | one row, `State` is `CONNECTED`, `Timestamp` moves every second |
| `http://localhost:8081/actuator/health` (B in Spring) | `UP`, with `deephaven` and `dhc-a` both `UP` |
| `http://localhost:8081/api/summary` | `"state":"CONNECTED"` and `"stale":false` |
| `http://localhost:8081/` | B's page: numbers from its REST API and two live tables |

In an environment with keys the web IDE asks for the key, and the page of `dhc-b-spring` takes
it as `?psk=<key>` and passes it on to the tables it embeds.

### The three health questions of a Spring host

| Address | Asks | `DOWN` or 503 means |
|---|---|---|
| `/actuator/health/liveness` | is this process well: Spring, and the embedded Deephaven | restart it |
| `/actuator/health/readiness` | can it do what it is for: the above, and the upstream | do not send it traffic; **do not restart it**, the upstream is what is away |
| `/actuator/health` | everything, with details | look at which part is down |

An instance B whose A is away answers liveness 200 and readiness 503. Restarting B does not
bring A back, and B reconnects by itself. A plain Java host has no such addresses: the scripts
ask its web IDE (`/ide/`), and `feed_status` in the IDE says what the upstream does.

## Where things are

| What | Started by `demo.sh` | Started by `run.sh` | In a container |
|---|---|---|---|
| log | `build/demo/<env>/<app>.log` | the terminal | `docker compose -f docker/compose.yaml logs` |
| process id | `build/demo/<env>/<app>.pid` | the shell's | the container's |
| the instance's own directory | `build/demo/<env>/<app>-work` | `build/run/<env>/<app>/work` | `/var/dhc/work` |
| settings | `config/` (or `DHC_CONFIG_DIR`) | the same | `/opt/dhc/config`, a copy made at build time |

The instance's own directory (`DHC_WORK_DIR`) holds Deephaven's compiled formulas, its
configuration copy and the web IDE's saved layouts and notebooks. It can be deleted while the
instance is stopped; the instance fills it again. Two instances never share one (F10).

Deephaven prints the key it uses when it starts. `demo.sh logs` leaves those lines out; the log
files themselves have them, so treat a log file of a key-protected environment as a secret.

## Changing a setting

Every setting is `DHC_<NAME>`, listed with its default and meaning in
[config/defaults.env](../config/defaults.env). Lowest first: the application's own defaults,
`defaults.env`, `apps/<app>.env`, `env/<environment>.env`, the process environment,
`-Ddhc.<name>`.

| To change it | Do |
|---|---|
| for one run | set it in the environment: `DHC_GEN_RATE=50 scripts/run.sh dhc-a-java` |
| for one application only, in a shell that starts both | scope it: `DHC_DHC_B_HTTP_PORT=10011` is `DHC_HTTP_PORT` for `dhc-b` |
| for an environment | edit `config/env/<environment>.env`, run `./gradlew validateConfig`, restart |
| in a container without a new image | pass an environment variable, or mount a directory over `/opt/dhc/config` |

No setting is read again while an application runs: restart it. The heap and other JVM options
(`DHC_JVM_HEAP`, `DHC_JVM_OPTS`) cannot be applied from inside the JVM, so they take effect
only when the application is started by `scripts/run.sh`, `scripts/demo.sh` or the container
entry point, not by a hand-written `java` command.

## When A goes away

This is the one event the pair is built to live through (F13,
[design section 4.1](01-design.md#41-embed-core)). Nothing has to be done; this is what to
expect.

1. B notices within about half a second of A's process ending. `feed_status` says
   `DISCONNECTED`, B logs `Lost upstream dhc-a ... Trying to reconnect.`, readiness goes to 503
   and `/api/summary` says `"stale":true`. B's tables keep their last rows and can still be
   read. `scripts/demo.sh status` then says B is running and does not answer at its readiness
   address, which is the same thing said by the script.
2. B tries A's address four times a second, for as long as it takes.
3. When A answers, B runs its application scripts again, which subscribe anew and rebuild every
   derived table. It logs `Reconnected to upstream dhc-a ...; the application scripts ran
   again.` and `Reconnects` in `feed_status` goes up by one.
4. A is in-memory. A restarted A starts its book again, so B's new tables start from what the
   new A has, not from where the old one stopped.

A browser that had a table of B open during the outage shows "Unable to open table" over the
last rows. Open the table again; the page of `dhc-b-spring` does that by itself when it sees
`reconnects` change.

An A that is **asked** to stop, not killed, takes about ten seconds over it while B is
connected, because Deephaven waits for its clients ([stopping](#stopping)). A stops answering
new requests at once. B was seen to notice at the start of those ten seconds in one run and at
their end in another, and in between it still says `CONNECTED` while nothing arrives. Until B
says `DISCONNECTED`, a `Timestamp` in `feed_status` that moves does not mean rows are coming:
look at whether `orders_a` grows.

To try all of this: `scripts/demo.sh down dhc-a-java`, look, `scripts/demo.sh up`.

A wrong or missing key is not an outage. A refuses it at once, B does not retry, and B's start
fails with the name of the setting to correct.

## Stopping

- With nobody connected, a stop takes one or two seconds.
- With browsers or another instance connected, Deephaven waits for them for up to ten seconds
  (`-Dshutdown.timeoutMs`) and then logs a `TimeoutException` from
  `JettyBackedGrpcServer.stopWithTimeout`. That exception is harmless; the stop goes on.
- `demo.sh down` waits forty seconds and then ends the process, and says which of the two
  happened. Afterwards it checks that the port is free again.
- In Windows Git Bash a process cannot be asked to stop. The scripts end it, with the JVM below
  the shell's own process. Nothing is lost by that: the instances keep nothing on disk that
  they need again.
- An embedded Deephaven cannot be started again in the same JVM (F10). "Restart" always means
  a new process.

Stop B before A when both go down. B then sees no outage, and A is not asked for anything while
it stops.

## Containers

```bash
./gradlew buildImages
docker compose -f docker/compose.yaml up -d      # or: podman compose ...
scripts/smoke.sh
docker compose -f docker/compose.yaml ps         # both should say healthy
docker compose -f docker/compose.yaml down
```

- The compose file sets `DHC_ENV=dev` and publishes the ports on `127.0.0.1` only, because
  `dev` lets anybody in. An image by itself sets no environment and then runs key-protected,
  with a key it makes up and prints.
- The container's health check is the liveness question: an instance B whose A is away stays
  `healthy` and is not restarted for it.
- Images run as the user `dhc` (10001) and write below `/var/dhc/work` only.
- For a company registry and base image, see [docker/README.md](../docker/README.md).

## Before a release

```bash
./gradlew check integrationTest
./gradlew installDist extractBootJar
scripts/demo.sh up && scripts/smoke.sh && scripts/demo.sh down
scripts/demo.sh up secondary && scripts/smoke.sh && scripts/demo.sh down
```

CI runs the same on Linux, macOS and Windows, plus one pair under `uat` with keys made up for
the run, and on Linux the images under compose. Moving to another Deephaven version has its own
list: [05, upgrade checklist](05-upgrade-checklist.md).
