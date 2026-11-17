# deephaven-community-embedded

Deephaven Community Core running **inside** your own Java applications: a Spring Boot application
and a plain Java application with its own `main`, each hosting an embedded Deephaven instance as
an internal UI and query module over its in-memory data. Embedded instance **A** publishes mock
order executions; embedded instance **B** subscribes to A and derives its own tables.

Deephaven 42.4 · Gradle 9.2 · Java 21 · Groovy application-mode modules.

Community Core only. Every Deephaven dependency is an open-source `io.deephaven` artifact from
Maven Central; nothing here uses or needs Deephaven Enterprise. The short name `dhc` in package
names, plugin ids and `DHC_*` keys is Deephaven Community Core.

## From clone to both UIs

You need a **JDK 21** (a JDK, not a JRE) on the `PATH` or in `JAVA_HOME`, plus `git`, `bash`
and `curl`. macOS and Linux have the last three; on Windows, Git for Windows brings all of them
and every command below is typed in Git Bash. Nothing else is installed by hand: the Gradle
wrapper downloads Gradle and the libraries, from Maven Central and the Gradle plugin portal.

```bash
git clone https://github.com/crazymatthsu/deephaven-community-embedded.git
cd deephaven-community-embedded
./gradlew installDist extractBootJar    # build every application; some minutes the first time
bash scripts/check_env.sh               # does this machine have what is needed?
bash scripts/demo.sh up                 # start instance A, wait for it, start instance B
```

`demo.sh up` ends by printing the addresses. Open them in a browser:

| Address | What you see |
|---|---|
| <http://localhost:10000/ide/> | **Instance A's Deephaven web IDE.** In the Panels menu: `orders`, `execs`, `exec_events`, `order_cache`, `cache_stats`. Open `orders` and watch it tick. |
| <http://localhost:10001/ide/> | **Instance B's Deephaven web IDE.** `orders_a` and `execs_a` are A's tables, subscribed; `cum_by_symbol`, `exec_rate` and `open_orders` are derived from them; `feed_status` says whether A is connected. |
| <http://localhost:8081/> | B's own page, served by Spring Boot: numbers from its REST API and two live Deephaven tables embedded in it. |

Then check it and stop it:

```bash
bash scripts/smoke.sh                   # B holds what A has; ends with "smoke: passed"
bash scripts/demo.sh down
```

To see the pair live through an outage, stop only A while both are up and start it again:

```bash
bash scripts/demo.sh down dhc-a-java    # takes about ten seconds while B is connected
bash scripts/demo.sh up                 # starts A again; B is left running
```

In between, B's page and `feed_status` say the feed is disconnected and keep the last rows.
After the second command B reconnects by itself and rebuilds its tables.

If a step fails, it says why. [docs/04-troubleshooting.md](docs/04-troubleshooting.md) is
ordered by the message you see.

## What is here

- **Instance A** shows mock order executions and the application's own order cache as five live
  tables.
- **Instance B** subscribes to A's tables, derives its own, and reconnects by itself when A goes
  away and comes back.
- Each runs in a plain Java host (`dhc-a-java`, `dhc-b-java`) or in a Spring Boot application
  (`dhc-a-spring`, `dhc-b-spring`). `demo.sh up` starts A in plain Java and B in Spring Boot;
  `demo.sh up secondary` starts the other pair.
- A Spring host has two ports: Spring MVC (8081 for B) for its REST API, health and page, and
  Deephaven's own server (10001 for B) for the web IDE and the tables.

| Address, while the primary pair is up | What |
|---|---|
| <http://localhost:8081/api/summary> | totals, and whether A is connected |
| <http://localhost:8081/api/orders/O0000001> | one order, read from a live Deephaven table |
| <http://localhost:8081/actuator/health> | `deephaven` and `dhc-a`; stop A and `dhc-a` goes down |
| <http://localhost:8081/actuator/health/liveness>, `/readiness` | "is this process well" and "can it do what it is for" |

### Other ways to start

| How | Command |
|---|---|
| One application from Gradle, for development | `./gradlew :apps:dhc-a-java:run`, `./gradlew :apps:dhc-b-spring:bootRun` |
| One application as built, in the foreground | `scripts/run.sh dhc-b-spring` |
| Another environment | `DHC_ENV=uat scripts/demo.sh up`, after setting the three keys it asks for |
| Containers | `./gradlew buildImages`, then `docker compose -f docker/compose.yaml up -d` (or `podman compose`) |

The environment `dev` lets anybody in, on localhost only. `uat` and `prod` ask for a key, and
the keys are never in a file: [config/README.md](config/README.md) names the three environment
variables. Spring Boot's fat jar is not a way to run an application here: Deephaven compiles
formulas with `javac`, which cannot read a jar inside a jar, so the application refuses to
start from one and says so. For the same reason it needs a JDK, not a JRE.

## Documents

| Document | What it holds |
|---|---|
| [docs/03-runbook.md](docs/03-runbook.md) | running it: start, check, stop, where the logs are, changing a setting, what happens when A goes away |
| [docs/04-troubleshooting.md](docs/04-troubleshooting.md) | symptom, cause, fix, for everything that went wrong while this was built |
| [docs/05-upgrade-checklist.md](docs/05-upgrade-checklist.md) | moving to another Deephaven version: the one class with the module list, what else to look at, what to run |
| [docs/00-feasibility-spike.md](docs/00-feasibility-spike.md) | what was run and what it showed: seventeen findings, including the three that would have broken a naive attempt |
| [docs/01-design.md](docs/01-design.md) | architecture, repository layout, build logic, the framework, the two roles, configuration for dev / UAT / production, packaging, testing, risks |
| [docs/02-implementation-phases.md](docs/02-implementation-phases.md) | the seven phases it was built in, with what each found that the plan had wrong |
| [config/](config/README.md), [scripts/](scripts/README.md), [docker/](docker/README.md), [build-logic/](build-logic/README.md) | each directory's own README |

All seven phases are built. Not done, and listed as open in the documents above: one-port mode
(the Spring application serving Deephaven's UI itself), Spring Boot 4.x, and TLS between the
instances.

## Build

Needs a JDK 21 on the machine. Everything else comes through the Gradle wrapper, from Maven
Central and the Gradle plugin portal only.

```bash
./gradlew check
```

`check` compiles every module and runs the unit and engine tests, `verifyDeephavenAlignment`
for every application, `validateConfig`, `checkScripts`, `checkDocs` and the build-logic tests.
`./gradlew integrationTest` runs the tests that need two processes. CI runs both, and then the
applications as shipped, on Linux, macOS and Windows.
Behind a company mirror, see [config/README.md](config/README.md).
The build rules and where each comes from are in [build-logic/README.md](build-logic/README.md).

## Layout

```
build-logic/   convention plugins, and the settings plugin that declares every repository
framework/     shared Java code: embed-core, embed-spring, embed-testkit, orders-model
apps/          embedded applications: dhc-a-module, dhc-b-module, dhc-a-java, dhc-b-spring, ...
config/        settings per application and per environment, artifact sources
scripts/       run, demo, smoke, check_env
docker/        images and compose
docs/          findings, design, phases, runbook, troubleshooting, upgrade checklist
```

## Licence

The code in this repository is MIT licensed. Deephaven Community Core itself is distributed under
the Deephaven Community License; read its terms before embedding it in a product you ship.
