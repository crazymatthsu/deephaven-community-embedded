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

Phase P5 of seven: roles A and B, A in a plain Java host, B in a plain Java host and in a
Spring Boot application. Instance A shows mock order executions and the application's own order
cache as five live tables. Instance B subscribes to A's tables, derives its own, and reconnects
by itself when A goes away and comes back. In Spring Boot, B also has a REST API, health
indicators and a page of its own with Deephaven's tables embedded in it.

```bash
./gradlew :apps:dhc-a-java:run          # in one terminal
./gradlew :apps:dhc-b-spring:bootRun    # in another; it waits for A
```

then open:

| Address | What |
|---|---|
| <http://localhost:8081/> | B's own page: numbers from its REST API, two live Deephaven tables |
| <http://localhost:8081/api/summary> | totals, and whether A is connected |
| <http://localhost:8081/actuator/health> | `deephaven` and `dhc-a`; stop A and `dhc-a` goes down |
| <http://localhost:10001/ide/> | B's Deephaven web IDE; `feed_status` says whether A is connected |
| <http://localhost:10000/ide/> | A's Deephaven web IDE |

A Spring host has two ports: Spring MVC on 8081, Deephaven's own server on 10001.
`./gradlew :apps:dhc-b-java:run` is the same role B without Spring.

`run` and `bootRun` are the development start: anonymous access, on localhost only. Outside
Gradle the applications ask for a key, which Deephaven prints at start unless `DHC_AUTH_PSK`
supplies one:

```bash
./gradlew :apps:dhc-a-java:installDist :apps:dhc-b-spring:extractBootJar
apps/dhc-a-java/build/install/dhc-a-java/bin/dhc-a-java
cd apps/dhc-b-spring/build/extracted && java @jvm.args -jar dhc-b-spring.jar
```

B has to present A's key: put it in `DHC_UPSTREAM_DHC_A_PSK` in B's environment, and give both
a key of your choosing with `DHC_AUTH_PSK`. B's page then opens as
`http://localhost:8081/?psk=<B's key>`.

Spring Boot's fat jar is not a way to run it: Deephaven compiles formulas with `javac`, which
cannot read a jar inside a jar, so the application refuses to start from one and says so.

| Document | What it holds |
|---|---|
| [docs/00-feasibility-spike.md](docs/00-feasibility-spike.md) | what was run and what it showed: fifteen findings, including the three that would have broken a naive attempt |
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
