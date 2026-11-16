#!/usr/bin/env bash
# Says whether this machine has what the build, the scripts and the containers need, before any
# of them fails halfway with a less helpful message. Changes nothing.
#
# Usage: scripts/check_env.sh          (bash scripts/check_env.sh on Windows)
#
# The environment is DHC_ENV (dev unless set). Exits 1 if something required is missing; what
# is only needed for part of the work (a container runtime, built applications) is reported and
# does not fail the check.
#
# Runs on macOS bash 3.2, Linux and Windows Git Bash.

set -eu
DHC_LIB_NAME=check_env
. "$(dirname "$0")/lib.sh"

root=$(dhc_root)
config=${DHC_CONFIG_DIR:-$root/config}
environment=${DHC_ENV:-dev}
missing=0

ok() {
    echo "  ok       $1"
}

optional() {
    echo "  missing  $1 (optional: $2)"
}

required() {
    echo "  MISSING  $1"
    echo "           $2"
    missing=$((missing + 1))
}

echo "check_env: machine"

ok "bash $BASH_VERSION"

java=$(dhc_java)
if command -v "$java" > /dev/null 2>&1; then
    # java -version writes to standard error: java version "21.0.9" / openjdk version "21.0.9"
    java_version=$("$java" -version 2>&1 | sed -n 's/.* version "\([0-9][0-9]*\).*/\1/p' | head -n 1)
    if [ -n "$java_version" ] && [ "$java_version" -ge 21 ]; then
        ok "java $java_version ($java)"
    else
        required "Java 21 or later; $java is version ${java_version:-unknown}" \
            "Install a JDK 21 and put it on the PATH or in JAVA_HOME. The Gradle build finds or downloads its own; the scripts do not."
    fi
else
    required "java" "Install a JDK 21 and put it on the PATH or in JAVA_HOME."
fi

if command -v curl > /dev/null 2>&1; then
    ok "curl"
else
    required "curl" "The scripts wait for the applications with curl. Git for Windows includes it."
fi

runtime=
for candidate in docker podman; do
    if command -v "$candidate" > /dev/null 2>&1; then
        runtime=$candidate
        break
    fi
done
if [ -n "$runtime" ]; then
    if "$runtime" info > /dev/null 2>&1; then
        ok "$runtime, and it answers"
    else
        optional "$runtime is installed but does not answer" "containers only; start Docker Desktop or 'podman machine start'"
    fi
    if "$runtime" compose version > /dev/null 2>&1; then
        ok "$runtime compose"
    else
        optional "$runtime compose" "docker/compose.yaml only"
    fi
else
    optional "docker or podman" "containers only; ./gradlew buildImages and docker/compose.yaml"
fi

echo "check_env: configuration"

if [ -f "$config/defaults.env" ]; then
    ok "$config"
else
    required "the configuration directory" "$config has no defaults.env. DHC_CONFIG_DIR says where it is."
fi
if [ -f "$config/env/$environment.env" ]; then
    ok "environment '$environment'"
else
    required "environment '$environment'" "There is no $config/env/$environment.env. DHC_ENV picks the environment."
fi
if [ -f "$root/config/repos/repos.env" ]; then
    ok "artifact sources: Maven $(dhc_file_value "$root/config/repos/repos.env" DHC_MAVEN_REPO_URL), registry $(dhc_file_value "$root/config/repos/repos.env" DHC_CONTAINER_REGISTRY)"
    if [ -f "$root/config/repos/repos.local.env" ]; then
        ok "config/repos/repos.local.env overrides some of them on this machine"
    fi
else
    required "config/repos/repos.env" "The build reads every download address from it."
fi

if [ "$missing" -eq 0 ] && [ -f "$config/env/$environment.env" ]; then
    echo "check_env: environment '$environment'"
    for app_id in dhc-a dhc-b; do
        mode=$(dhc_setting "$config" "$app_id" "$environment" DHC_AUTH_MODE)
        port=$(dhc_setting "$config" "$app_id" "$environment" DHC_HTTP_PORT)
        web=$(dhc_setting "$config" "$app_id" "$environment" DHC_WEB_PORT)
        heap=$(dhc_setting "$config" "$app_id" "$environment" DHC_JVM_HEAP)
        ok "$app_id: Deephaven port $port, application port $web (Spring host), login $mode, heap $heap"
        if [ "$mode" != anonymous ] && [ -z "$(dhc_setting "$config" "$app_id" "$environment" DHC_AUTH_PSK)" ]; then
            optional "$(dhc_scoped_key "$app_id" DHC_AUTH_PSK)" "scripts/demo.sh needs it in this environment; without it Deephaven makes a key up and prints it"
        fi
        for used in $port $web; do
            if curl -s -o /dev/null --max-time 2 "http://localhost:$used/"; then
                echo "  note     something answers on port $used already"
            fi
        done
    done
    if [ "$(dhc_setting "$config" dhc-a "$environment" DHC_AUTH_MODE)" != anonymous ] \
        && [ -z "$(dhc_setting "$config" dhc-b "$environment" DHC_UPSTREAM_DHC_A_PSK)" ]; then
        optional "DHC_UPSTREAM_DHC_A_PSK" "instance B presents it to A in this environment"
    fi
fi

echo "check_env: what is built"
for app in $DHC_APPS; do
    if dhc_app_built "$root" "$app"; then
        ok "$app"
    else
        optional "$app" "$(dhc_build_command "$app")"
    fi
done
if [ -f "$root/apps/dhc-probe/build/install/dhc-probe/bin/dhc-probe" ]; then
    ok "dhc-probe"
else
    optional "dhc-probe" "scripts/smoke.sh needs it; ./gradlew :apps:dhc-probe:installDist"
fi

if [ "$missing" -gt 0 ]; then
    dhc_fail "$missing required thing(s) missing"
fi
dhc_say "this machine has what is required"
