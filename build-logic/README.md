# build-logic

The convention plugins. A module's build file names a plugin and its dependencies; everything
else is decided here, once. Findings "F*n*" are in
[docs/00-feasibility-spike.md](../docs/00-feasibility-spike.md).

| Plugin | For | What it fixes |
|---|---|---|
| `dhe.repositories` | the settings of every build | Repositories come from `config/repos/repos.env` and nowhere else. A module that declares its own fails the build. |
| `dhe.java-conventions` | every Java module | Java 21 toolchain, UTF-8, warnings as errors, JUnit 5, reproducible archives. |
| `dhe.deephaven-library` | `framework/*`, role modules | Deephaven BOM enforced (F9), Kafka extension excluded (F2), Dagger and its processor (F1), JVM flags on tests (F3). |
| `dhe.groovy-app-module` | `apps/*-module` | The same as `dhe.deephaven-library` for now. Packaging of `src/main/appmode/` and `validateAppModule` come in P2. |
| `dhe.embedded-java-app` | plain Java hosts | `application` plugin, the dependency rules, JVM flags on `run`, tests and start scripts (F3), `verifyDeephavenAlignment`. |
| `dhe.embedded-spring-app` | Spring hosts | Spring Boot plugin without `io.spring.dependency-management`, Spring's BOM as a plain platform, Deephaven's versions pinned (F9), Tomcat WebSocket excluded (F8), JVM flags on `bootRun` and tests (F3), `verifyDeephavenAlignment`. `extractBootJar` comes in P5. |
| `dhe.verify-alignment` | every app (applied by the two app plugins) | `verifyDeephavenAlignment`, part of `check`. |

## Two builds

`repositories-plugin/` is a build of its own, in plain Java with no dependencies. That is what
lets build-logic take its repositories from the plugin too: build-logic needs repositories to
fetch the Kotlin DSL and Spring Boot plugins, so the plugin that declares them cannot be built by
build-logic itself.

## verifyDeephavenAlignment

For an application the task resolves two graphs and compares them:

1. the application's runtime classpath;
2. "Deephaven alone": the `io.deephaven` modules found on that classpath, resolved under the
   Deephaven BOM with nothing else.

Any library in graph 2 that has a different version in graph 1 fails the build, with both
versions and the `dependencyInsight` command that explains the difference. Libraries Deephaven
does not bring are not compared. An application with no Deephaven module at all also fails,
because a check with nothing to compare would pass for the wrong reason.

Graph 2 is resolved in a detached configuration, so a project-wide
`configurations.all { resolutionStrategy.force(...) }` changes the application and not the
reference.

## Spring hosts and versions

Spring Boot's BOM is a plain platform: it suggests versions. Where Deephaven needs a higher
version than Spring manages (Jetty, Netty), the higher one wins by itself. Where Spring manages a
higher version than Deephaven uses (Jackson, Gson, HttpClient and others), it would win the same
way, so `dhe.embedded-spring-app` pins every library that Deephaven brings to the version
Deephaven alone resolves. `dependencyInsight` shows the pin as
"Deephaven 42.4 alone resolves …". Spring decides the libraries Deephaven does not bring.

## Tests

`src/test/fixtures/` holds three small Gradle builds. `ConventionPluginsTest` runs them with
Gradle TestKit and checks that each rule holds and that breaking one fails with a message that
says why. They run as part of `./gradlew check` at the root. To run one by hand:

```bash
./gradlew -p build-logic/src/test/fixtures/java-host -PdheRepoRoot="$PWD" :app:verifyDeephavenAlignment -PforceOlderJetty
```
