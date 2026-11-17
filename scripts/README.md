# scripts

Everything here runs on macOS bash 3.2, Linux and Windows Git Bash (there: `bash scripts/<name>`).
No GNU-only flags, no associative arrays. Each script says at its top what it needs.

`./gradlew checkScripts` (part of `check`) holds every script here and in `docker/` to that
rule, by reading them: it names the line that uses something bash 3.2 or the BSD tools do not
have. CI then runs them on all three systems.

| Script | What |
|---|---|
| `check_env.sh` | Says whether this machine has what the build, the scripts and the containers need: bash, a Java 21, curl, a container runtime, the configuration, what is built. Changes nothing. |
| `run.sh <app> [environment]` | Starts one application in the foreground, from what Gradle built, with the settings of one environment. Reads the heap and other JVM options from the configuration, because they cannot be set from inside the JVM. |
| `demo.sh up [pair] \| down [app] \| status \| logs [app]` | Starts instance A and then instance B in the background and stops them. `down <app>` stops one of the two and waits until it is gone; `up` then starts it again and leaves the other running, which is how to watch B live through an outage of A. `primary` is `dhc-a-java` with `dhc-b-spring`, `secondary` is `dhc-a-spring` with `dhc-b-java`. Logs and process ids are in `build/demo/<environment>/`. |
| `smoke.sh [pair]` | Checks a running pair from outside: both answer, and what B holds is what A has. Works against `demo.sh up` and against the containers of `docker/compose.yaml`. |
| `check-start.sh <app> [port]` | Starts an installed plain Java host from its start script with nothing but its defaults, waits until its web IDE answers, and stops it. |
| `sync-gradle-wrapper.sh` | Copies the Gradle download address from `config/repos/repos.env` (and `repos.local.env`) into `gradle/wrapper/gradle-wrapper.properties`. Needs no Gradle and no network. Run it after changing `DHC_GRADLE_DISTRIBUTIONS_URL` or the Gradle version; see [config/README.md](../config/README.md). |
| `lib.sh` | What the others share, and the container entry point too: reading settings with the same rules as the applications, the list of applications, waiting and stopping. Sourced, never run. |

## The usual order

```bash
./gradlew installDist extractBootJar    # what run.sh, demo.sh and smoke.sh start
scripts/check_env.sh
scripts/demo.sh up                      # or: scripts/demo.sh up secondary
scripts/smoke.sh
scripts/demo.sh status
scripts/demo.sh logs
scripts/demo.sh down
```

## Environments

`DHC_ENV` picks `config/env/<environment>.env`; `dev` unless set. Two environments have ports of
their own, so they run side by side:

```bash
export DHC_DHC_A_AUTH_PSK=...  DHC_DHC_B_AUTH_PSK=...  DHC_UPSTREAM_DHC_A_PSK=...   # see config/README.md
DHC_ENV=uat scripts/demo.sh up
DHC_ENV=uat scripts/smoke.sh
DHC_ENV=uat scripts/demo.sh down
```

Whatever is set in the environment of a script wins over the files:
`DHC_GEN_RATE=50 scripts/run.sh dhc-a-java`.

## What the smoke check compares

A's feed does not stop, so there is no moment at which A and B could be compared as equal. The
script reads A, then B, then A again, and asks that B's number of orders and filled quantity lie
between A's two readings. Both only grow, so B is then exactly what A was at some moment in
between. It tries a few times, because B is allowed to be a moment behind.

## Stopping, and Windows

`demo.sh down` asks an application to stop and waits; with nobody connected that takes a second
or two. In Windows Git Bash a process cannot be asked: the scripts end it, together with the JVM
below the shell's own process, and then check that its port is free again.

Deephaven prints the key it uses when it starts. `demo.sh logs` and the scripts' own error
output leave those lines out; the log files themselves have them.
