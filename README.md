# deephaven-community-embedded

Deephaven Community Core running **inside** your own Java applications: a Spring Boot application
and a plain Java application with its own `main`, each hosting an embedded Deephaven instance as
an internal UI and query module over its in-memory data. Embedded instance **A** publishes mock
order executions; embedded instance **B** subscribes to A and derives its own tables.

Deephaven 42.4 · Gradle 9.2 · Java 21 · Groovy application-mode modules.

## Status

Phase P1 of seven: the build skeleton. Every module exists and resolves against Deephaven 42.4,
and the build rules from the spike are enforced, but nothing runs yet. The embedding was proven
in a throwaway spike before anything was designed; the code arrives in phases, one pull request
each.

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
