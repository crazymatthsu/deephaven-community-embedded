#!/usr/bin/env bash
# Starts one application in the foreground, from what Gradle built, with the settings of one
# environment. Ctrl-C stops it.
#
# Usage: scripts/run.sh <app> [environment]      for example: scripts/run.sh dhc-b-spring uat
#        (bash scripts/run.sh ... on Windows)
#
#   app          dhc-a-java, dhc-a-spring, dhc-b-java or dhc-b-spring
#   environment  dev (the default), uat or prod: config/env/<environment>.env
#
# Build first: ./gradlew :apps:<app>:installDist for a plain Java host,
#              ./gradlew :apps:<app>:extractBootJar for a Spring Boot host.
#
# The settings come from config/ (DHC_CONFIG_DIR says otherwise), and whatever is set in the
# environment of this script wins over the files. The heap and other JVM options are settings
# too (DHC_JVM_HEAP, DHC_JVM_OPTS); they cannot be applied from inside the JVM, so this script
# reads them. Keys are never in the files: see config/README.md.
#
# Runs on macOS bash 3.2, Linux and Windows Git Bash.

set -eu
DHC_LIB_NAME=run
. "$(dirname "$0")/lib.sh"

[ $# -ge 1 ] || dhc_fail "usage: scripts/run.sh <app> [environment]. Applications: $DHC_APPS"
app=$1
environment=${2:-${DHC_ENV:-dev}}

root=$(dhc_root)
config=${DHC_CONFIG_DIR:-$root/config}
app_id=$(dhc_app_id "$app")
kind=$(dhc_app_kind "$app")
home=$(dhc_app_home "$root" "$app")

[ -f "$config/defaults.env" ] || dhc_fail "$config is not the configuration directory (no defaults.env)"
[ -f "$config/env/$environment.env" ] || dhc_fail "there is no environment '$environment': no $config/env/$environment.env"
dhc_app_built "$root" "$app" || dhc_fail "$app is not built. Run: $(dhc_build_command "$app")"

heap=$(dhc_setting "$config" "$app_id" "$environment" DHC_JVM_HEAP)
options=$(dhc_setting "$config" "$app_id" "$environment" DHC_JVM_OPTS)
jvm="${heap:+-Xmx$heap}${options:+ $options}"

export DHC_CONFIG_DIR="$config"
export DHC_ENV="$environment"
# The instance's own directory (F10): one per application and environment, so that dev and uat
# can run side by side.
export DHC_WORK_DIR="${DHC_WORK_DIR:-$root/build/run/$environment/$app/work}"
mkdir -p "$DHC_WORK_DIR"

if [ "$kind" = java ]; then
    # The start script of the distribution adds the mandatory JVM flags itself.
    JAVA_OPTS="${JAVA_OPTS:-}${jvm:+ $jvm}"
    export JAVA_OPTS
    exec "$home/bin/$app"
else
    cd "$home"
    # jvm.args holds the mandatory JVM flags. The word splitting of $jvm is intended.
    # shellcheck disable=SC2086
    exec "$(dhc_java)" @jvm.args $jvm -jar "$app.jar"
fi
