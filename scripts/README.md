# scripts

| Script | What |
|---|---|
| `check-start.sh` | Starts an installed plain Java host from its start script, waits until its web IDE answers, and stops it. `scripts/check-start.sh dhc-a-java`, after `./gradlew :apps:dhc-a-java:installDist`. CI runs it on Linux and in Windows Git Bash. |
| `sync-gradle-wrapper.sh` | Copies the Gradle download address from `config/repos/repos.env` (and `repos.local.env`) into `gradle/wrapper/gradle-wrapper.properties`. Needs no Gradle and no network. Run it after changing `DHC_GRADLE_DISTRIBUTIONS_URL` or the Gradle version; see [config/README.md](../config/README.md). |

The run scripts arrive in phase P6: `run.sh`, `demo.sh`, `smoke.sh`, `check_env.sh` and a
shared `lib.sh` (design section 7).

Rules for everything that lands here: it has to run on macOS bash 3.2, Linux and Windows Git
Bash. No GNU-only flags, no associative arrays.
