# config

Settings as data: the same commit runs in every environment, and only the files here differ
(design section 6).

| Path | What | Since |
|---|---|---|
| `repos/repos.env` | Where the build gets its artifacts: Maven repository, Gradle plugin repository, Gradle distributions, container registry. Public defaults. | P1 |
| `repos/repos.local.env.example` | Template for a company mirror. Copy it to `repos/repos.local.env`, which is git-ignored. | P1 |
| `defaults.env`, `apps/*.env`, `env/{dev,uat,prod}.env` | Application and environment settings, with `validateConfig`. | P6 |

## Artifact sources

`repos/repos.env` is read by the `dhc.repositories` settings plugin, which is the only place this
build declares repositories. A module that declares one of its own fails the build.

To build behind a company Artifactory:

```bash
cp config/repos/repos.local.env.example config/repos/repos.local.env
```

then uncomment the keys you need. Keys you leave out keep their public defaults. After changing
`DHC_GRADLE_DISTRIBUTIONS_URL`, run `./gradlew wrapper` so the wrapper downloads Gradle from
there too.

No user name, password or token goes in either file. If the mirror needs a login, set
`DHC_REPO_CREDENTIALS=gradle-properties` and give Gradle the credentials as Gradle properties
(`dhcMavenUsername`, `dhcMavenPassword`, `dhcPluginsUsername`, `dhcPluginsPassword`), in
`~/.gradle/gradle.properties` or as `ORG_GRADLE_PROJECT_*` environment variables.

The format is the plain `KEY=value` subset that Gradle, bash and compose `env_file` all read the
same way: no quotes, no spaces around `=`, no variable expansion, `#` for comment lines. An
unknown key is an error, so a typo does not silently fall back to a default.
