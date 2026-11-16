# build-logic

The convention plugins. A module's build file names a plugin and its dependencies; everything
else is decided here, once. Findings "F*n*" are in
[docs/00-feasibility-spike.md](../docs/00-feasibility-spike.md).

| Plugin | For | What it fixes |
|---|---|---|
| `dhc.repositories` | the settings of every build | Repositories come from `config/repos/repos.env` and nowhere else. A module that declares its own fails the build. |
| `dhc.java-conventions` | every Java module | Java 21 toolchain, UTF-8, warnings as errors, JUnit 5, reproducible archives. |
| `dhc.deephaven-library` | `framework/*`, role modules | Deephaven BOM enforced (F9), Kafka extension excluded (F2), Dagger and its processor (F1), JVM flags on tests (F3), a JVM per test class (F10), javac's `classfile` lint off. |
| `dhc.groovy-app-module` | `apps/*-module` | Everything of `dhc.deephaven-library`, plus the Groovy part in `src/main/appmode/` (`<id>.app` and its scripts under `<id>/`): `validateAppModule` checks it on the module's own classpath, `generateAppModuleIndex` lists its files, and both go into the jar under `META-INF/dhc/app.d/`. A module that does not validate is not packaged. |
| `dhc.embedded-java-app` | plain Java hosts | `application` plugin, the dependency rules, JVM flags on `run`, tests and start scripts (F3), Logback as the SLF4J backend, `verifyDeephavenAlignment`. The start scripts name one jar, whose manifest lists the others, because Windows cannot hold the full list on a command line. `run` takes its settings from the repository's `config/` with the environment `dev`, and a work directory under `build/`, unless the environment says otherwise; the installed distribution gets neither. |
| `dhc.embedded-spring-app` | Spring hosts | Spring Boot plugin without `io.spring.dependency-management`, Spring's BOM as a plain platform, Deephaven's versions pinned (F9), Tomcat WebSocket excluded (F8), JVM flags on `bootRun` and tests (F3), `verifyDeephavenAlignment`. `extractBootJar` unpacks the fat jar into the layout an application is run from (F6, F7), with the JVM flags in `jvm.args`. `bootRun` gets the same development settings as `run`. |
| `dhc.verify-alignment` | every app (applied by the two app plugins) | `verifyDeephavenAlignment`, part of `check`. |
| `dhc.config` | `config/` | `validateConfig`, part of `check`: the settings files keep their format, hold no secret and no unknown key, and every environment has the same keys. `configArchive` packages them. |
| `dhc.scripts` | `scripts/` | `checkScripts`, part of `check`: nothing in `scripts/*.sh` and `docker/*.sh` that macOS bash 3.2 or the BSD tools refuse, no Windows line endings, `set -eu` in every script that is run. It reads the scripts as text and runs nothing. A line that is meant ends with `# checkScripts: allow`. |
| `dhc.docs` | `docs/` | `checkDocs`, part of `check`: every link between the Markdown documents of the repository leads to a file and a heading that exist. Links to other sites are not followed. |

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
way, so `dhc.embedded-spring-app` pins every library that Deephaven brings to the version
Deephaven alone resolves. `dependencyInsight` shows the pin as
"Deephaven 42.4 alone resolves …". Spring decides the libraries Deephaven does not bring.

## Tests

`src/test/fixtures/` holds seven small Gradle builds, one for each set of rules. One of them,
`repo-login`, has its own `config/repos/repos.env` for a mirror that needs a login and resolves
nothing; `config-check`, `scripts-check` and `docs-check` are correct as they are, and the tests
break them one way at a time. `ConventionPluginsTest` runs them with
Gradle TestKit and checks that each rule holds and that breaking one fails with a message that
says why. They run as part of `./gradlew check` at the root. To run one by hand:

```bash
./gradlew -p build-logic/src/test/fixtures/java-host -PdhcRepoRoot="$PWD" :app:verifyDeephavenAlignment -PforceOlderJetty
```
