# docker

Container images of the four hosts, and a compose file that runs instance A and instance B.
Works with docker and with podman.

| File | What |
|---|---|
| `Dockerfile.java-app` | a plain Java host (`dhc-a-java`, `dhc-b-java`): the `installDist` distribution |
| `Dockerfile.spring-app` | a Spring Boot host (`dhc-a-spring`, `dhc-b-spring`): the extracted layout, never the fat jar |
| `entrypoint.sh` | reads the JVM settings from the configuration directory, then becomes the JVM |
| `healthcheck.sh` | "is this process well": Deephaven's web IDE, or `/actuator/health/liveness` for a Spring host |
| `compose.yaml` | A and B together |
| `build.gradle.kts` | `buildImages` |

## Build and run

```bash
./gradlew buildImages                              # dhc/<app>:<version> and dhc/<app>:latest, all four
docker compose -f docker/compose.yaml up -d        # or: podman compose -f docker/compose.yaml up -d
scripts/smoke.sh                                   # the same check as for scripts/demo.sh
docker compose -f docker/compose.yaml down
```

`buildImages` uses docker if it is on the PATH, else podman; `DHC_CONTAINER_RUNTIME` says
otherwise. The secondary pair, A in Spring Boot and B in plain Java:

```bash
DHC_A_IMAGE=dhc/dhc-a-spring DHC_B_IMAGE=dhc/dhc-b-java docker compose -f docker/compose.yaml up -d
scripts/smoke.sh secondary
```

## The base image

`FROM <DHC_CONTAINER_REGISTRY>/<DHC_CONTAINER_BASE_IMAGE>`, both from
[config/repos/repos.env](../config/repos/repos.env): `docker.io/library/eclipse-temurin:21-jdk`
unless changed. To build from a company registry, change those two keys (or override them for
your machine in `repos.local.env`) and log in to the registry with your container runtime.

Whatever image is used has to be:

- **a JDK 21, not a JRE.** Deephaven compiles query formulas with `javac` while it runs. On a
  JRE the application stops at start and says so (finding F16).
- **with bash and curl**, for the entry point and the health check. The build fails with that
  message if either is missing.
- **with `groupadd` and `useradd`**, which create the user the application runs as. An image
  that has other tools for that needs those two lines of the Dockerfile changed.

## What is in an image

| Path | What |
|---|---|
| `/opt/dhc/app` | the application, as it is run outside a container |
| `/opt/dhc/config` | `defaults.env`, `apps/`, `env/`: a copy of the repository's settings at build time |
| `/var/dhc/work` | the instance's own directory; the only place it writes |

It runs as the user `dhc` (10001), not root.

## Settings

The same as outside a container ([config/README.md](../config/README.md)): the files in
`/opt/dhc/config`, and environment variables over them.

- `DHC_ENV` is **not** set in an image. Without it an instance runs on the safe defaults:
  key-protected, with a key it makes up and prints. The compose file sets `dev`.
- To change settings without a new image, mount a directory over `/opt/dhc/config`, or pass
  environment variables.
- The image sets `DHC_HTTP_HOST=0.0.0.0`: inside a container Deephaven has to listen on every
  interface to be reachable at all. What is reachable from outside is decided by what is
  published.
- Keys come from the environment of the compose command: `DHC_DHC_A_AUTH_PSK`,
  `DHC_DHC_B_AUTH_PSK`, `DHC_UPSTREAM_DHC_A_PSK`.

The compose file fixes three things whatever the environment says: the ports, which have to
match the ones it publishes; A's address for B, which is the service name; and that the
published ports stay on this machine (`127.0.0.1`), because `dev` lets anybody in.

## Health

The image's health check asks whether the process is well, and leaves the upstream out: an
instance B whose A is away is still well, and restarting it would not bring A back. For routing
traffic, a Spring host also answers `/actuator/health/readiness`, which does include the
upstream.

Podman keeps a `HEALTHCHECK` only in docker's image format, so `buildImages` asks for that
format when it runs podman.
