# config

Settings as data: the same commit runs in every environment, and only the files here differ
(design section 6).

| Path | What | Since |
|---|---|---|
| `repos/repos.env` | The one file that says where the build gets its artifacts: Maven repository, Gradle plugin repository, Gradle distributions, container registry. Public defaults. | P1 |
| `repos/repos.local.env.example` | Template for overriding `repos.env` on one machine. Copy it to `repos/repos.local.env`, which is git-ignored. | P1 |
| `defaults.env` | Every setting of the applications, with its default and what it means. A key that is not here is not a setting. | P6 |
| `apps/dhc-a.env`, `apps/dhc-b.env` | What makes an application that application, wherever it runs: its module, its default ports. | P6 |
| `env/dev.env`, `env/uat.env`, `env/prod.env` | What differs between environments: who may log in, interfaces, ports, heap, where instance A is. | P6 |

## Application settings

Lowest first: the application's own defaults, `defaults.env`, `apps/<app>.env`,
`env/<environment>.env`, the process environment, `-Ddhc.<name>`. `DHC_HTTP_PORT` and
`dhc.http.port` are the same setting.

- **Pick an environment** with `DHC_ENV`: `DHC_ENV=uat scripts/demo.sh up`,
  `scripts/run.sh dhc-b-spring uat`. `run`, `bootRun` and the scripts use `dev` unless told.
- **One file serves both applications.** What differs between them is scoped: in
  `env/uat.env`, `DHC_DHC_B_HTTP_PORT=11001` is `DHC_HTTP_PORT` for the application `dhc-b`. A
  scoped key wins over the plain one in the same place, and the same works in the process
  environment.
- **Change something for one run** by setting it in the environment:
  `DHC_GEN_RATE=50 scripts/run.sh dhc-a-java`.
- **Add a setting**: put it in `defaults.env` with a comment, then use it. **Add an
  environment**: add `env/<name>.env` with the same keys as the others.
- **`./gradlew validateConfig`** (part of `check`) refuses a key that is not in `defaults.env`,
  a key that one environment has and another has not, and anything that looks like a secret.

### Keys

No key, password or token is in any file here. An environment that asks for keys (`uat`,
`prod`) gets them from the environment of the process, from wherever that environment keeps
its secrets:

| Variable | What |
|---|---|
| `DHC_DHC_A_AUTH_PSK` | the key instance A asks its clients for |
| `DHC_DHC_B_AUTH_PSK` | the key instance B asks its clients for |
| `DHC_UPSTREAM_DHC_A_PSK` | A's key again, for B to present when it subscribes |

Without its key an instance makes one up and prints it at start. That is fine for one instance
alone and not for a pair: B cannot know a key A made up, so `scripts/demo.sh` asks for all
three before it starts anything.

## Artifact sources

`repos/repos.env` is read by the `dhc.repositories` settings plugin, which is the only place this
build declares repositories. A module that declares one of its own fails the build.

### Moving to a company mirror (JFrog Artifactory)

Two files hold everything: one in the repository that says **where**, one on your machine that
says **who**.

| File | Holds | Committed |
|---|---|---|
| `config/repos/repos.env` | the addresses: Maven repository, Gradle plugin repository, Gradle distributions, container registry and base image; and whether a login is needed | yes |
| `~/.gradle/gradle.properties` | the login, only if the mirror needs one | never; it is outside the repository |

1. Put your addresses in `config/repos/repos.env`. `repos.local.env.example` shows the shape of
   each for an Artifactory.

   ```
   DHC_MAVEN_REPO_URL=https://artifactory.example.com/artifactory/maven-remote/
   DHC_GRADLE_PLUGIN_REPO_URL=https://artifactory.example.com/artifactory/gradle-plugins-remote/
   DHC_GRADLE_DISTRIBUTIONS_URL=https://artifactory.example.com/artifactory/gradle-distributions-remote
   DHC_REPO_CREDENTIALS=gradle-properties
   DHC_CONTAINER_REGISTRY=artifactory.example.com/docker-remote
   DHC_CONTAINER_BASE_IMAGE=library/eclipse-temurin:21-jdk
   ```

2. Copy the Gradle address into the wrapper. The wrapper runs before any build logic and reads
   only `gradle/wrapper/gradle-wrapper.properties`, so this is the one address kept twice. The
   script needs no Gradle and no network:

   ```bash
   scripts/sync-gradle-wrapper.sh
   ```

3. If the mirror needs a login, add it to `~/.gradle/gradle.properties`. The first pair is for
   libraries and plugins, the second is Gradle's own pair for the wrapper's download.

   ```
   dhcRepoUsername=<your user name>
   dhcRepoPassword=<your identity token>
   systemProp.gradle.wrapperUser=<your user name>
   systemProp.gradle.wrapperPassword=<your identity token>
   ```

   In CI, use the environment variables `ORG_GRADLE_PROJECT_dhcRepoUsername` and
   `ORG_GRADLE_PROJECT_dhcRepoPassword` from the CI secret store.

4. Run `./gradlew check`. It fails with a message if the wrapper still points somewhere else,
   or if a login is asked for and not given.

To move only your own machine and leave the repository on the public defaults, put the same
keys in `config/repos/repos.local.env` instead of step 1. That file is git-ignored and overrides
`repos.env` key by key; start from the example:

```bash
cp config/repos/repos.local.env.example config/repos/repos.local.env
```

No user name, password or token goes in `repos.env` or `repos.local.env`; an address with
credentials in it is rejected.

Not covered by these files, because the build does not download them: the JDK (a JDK 21 has to
be on the machine) and whatever your CI system fetches for itself.

Container images are built `FROM <DHC_CONTAINER_REGISTRY>/<DHC_CONTAINER_BASE_IMAGE>`. Logging in
to that registry is the container runtime's business (`docker login`, `podman login`), not this
build's. The base image has to be a JDK 21 with bash and curl; see [docker/README.md](../docker/README.md).

The format is the plain `KEY=value` subset that Gradle, bash and compose `env_file` all read the
same way: no quotes, no spaces around `=`, no variable expansion, `#` for comment lines. An
unknown key is an error, so a typo does not silently fall back to a default.
