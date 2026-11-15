# config

Settings as data: the same commit runs in every environment, and only the files here differ
(design section 6).

| Path | What | Since |
|---|---|---|
| `repos/repos.env` | The one file that says where the build gets its artifacts: Maven repository, Gradle plugin repository, Gradle distributions, container registry. Public defaults. | P1 |
| `repos/repos.local.env.example` | Template for overriding `repos.env` on one machine. Copy it to `repos/repos.local.env`, which is git-ignored. | P1 |
| `defaults.env`, `apps/*.env`, `env/{dev,uat,prod}.env` | Application and environment settings, with `validateConfig`. | P6 |

## Artifact sources

`repos/repos.env` is read by the `dhc.repositories` settings plugin, which is the only place this
build declares repositories. A module that declares one of its own fails the build.

### Moving to a company mirror (JFrog Artifactory)

Two files hold everything: one in the repository that says **where**, one on your machine that
says **who**.

| File | Holds | Committed |
|---|---|---|
| `config/repos/repos.env` | the four addresses: Maven repository, Gradle plugin repository, Gradle distributions, container registry; and whether a login is needed | yes |
| `~/.gradle/gradle.properties` | the login, only if the mirror needs one | never; it is outside the repository |

1. Put your addresses in `config/repos/repos.env`. `repos.local.env.example` shows the shape of
   each for an Artifactory.

   ```
   DHC_MAVEN_REPO_URL=https://artifactory.example.com/artifactory/maven-remote/
   DHC_GRADLE_PLUGIN_REPO_URL=https://artifactory.example.com/artifactory/gradle-plugins-remote/
   DHC_GRADLE_DISTRIBUTIONS_URL=https://artifactory.example.com/artifactory/gradle-distributions-remote
   DHC_REPO_CREDENTIALS=gradle-properties
   DHC_CONTAINER_REGISTRY=artifactory.example.com/docker-remote
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
be on the machine) and whatever your CI system fetches for itself. Container images are not
built yet; from P6 they read `DHC_CONTAINER_REGISTRY`.

The format is the plain `KEY=value` subset that Gradle, bash and compose `env_file` all read the
same way: no quotes, no spaces around `=`, no variable expansion, `#` for comment lines. An
unknown key is an error, so a typo does not silently fall back to a default.
