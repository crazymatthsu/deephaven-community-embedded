# deephaven-community-embedded

Deephaven Community Core running **inside** your own Java applications: a Spring Boot application
and a plain Java application with its own `main`, each hosting an embedded Deephaven instance as
an internal UI and query module over its in-memory data. Embedded instance **A** publishes mock
order executions; embedded instance **B** subscribes to A and derives its own tables.

Deephaven 42.4 · Gradle 9.2 · Java 21 · Groovy application-mode modules.

Community Core only. Every Deephaven dependency is an open-source `io.deephaven` artifact from
Maven Central; nothing here uses or needs Deephaven Enterprise. The short name `dhc` in package
names, plugin ids and `DHC_*` keys is Deephaven Community Core.

## Status

Phase P3 of seven: role A. A plain Java `main` starts Deephaven in its own JVM and shows mock
order executions and the application's own order cache as five live tables (`exec_events`,
`execs`, `orders`, `order_cache`, `cache_stats`). The subscriber (role B) and the Spring Boot
host arrive in later phases, one pull request each.

```bash
./gradlew :apps:dhc-a-java:run
```

then open <http://localhost:10000/ide/>. `run` is the development start: anonymous access, on
localhost only. The installed distribution (`./gradlew :apps:dhc-a-java:installDist`, then
`apps/dhc-a-java/build/install/dhc-a-java/bin/dhc-a-java`) asks for a key, which Deephaven prints
at start unless `DHC_AUTH_PSK` supplies one.

| Document | What it holds |
|---|---|
| [docs/00-feasibility-spike.md](docs/00-feasibility-spike.md) | what was run and what it showed: ten findings, including the three that would have broken a naive attempt |
| [docs/01-design.md](docs/01-design.md) | architecture, repository layout, build logic, the framework, the two roles, configuration for dev / UAT / production, packaging, testing, risks |
| [docs/02-implementation-phases.md](docs/02-implementation-phases.md) | seven phases with deliverables and acceptance checks, and the decisions still open |

## Build

Needs a JDK 21 on the machine. Everything else comes through the Gradle wrapper, from Maven
Central and the Gradle plugin portal only.

```bash
./gradlew check
```

`check` compiles every module, runs `verifyDeephavenAlignment` for the four host applications
and runs the build-logic tests. Behind a company mirror, see [config/README.md](config/README.md).
The build rules and where each comes from are in [build-logic/README.md](build-logic/README.md).

## Layout

```
build-logic/   convention plugins, and the settings plugin that declares every repository
framework/     shared Java code: embed-core, embed-spring, embed-testkit, orders-model
apps/          embedded applications: dhc-a-module, dhc-b-module, dhc-a-java, dhc-b-spring, ...
config/        settings per application and per environment, artifact sources
scripts/       run, demo, smoke
docker/        images and compose
docs/
```

## Licence

The code in this repository is MIT licensed. Deephaven Community Core itself is distributed under
the Deephaven Community License; read its terms before embedding it in a product you ship.
