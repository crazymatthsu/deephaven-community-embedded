#!/usr/bin/env bash
# Entry point of both images. Starts the application the image was built for (DHC_APP) with the
# JVM settings of the configuration directory, then becomes that JVM: signals reach it directly,
# so "docker stop" is an orderly stop.
#
# The heap and other JVM options are settings (DHC_JVM_HEAP, DHC_JVM_OPTS) that cannot be applied
# from inside the JVM. scripts/run.sh reads them outside a container; this does the same inside,
# with the same functions (lib.sh).

set -eu
DHC_LIB_NAME=entrypoint
. /opt/dhc/lib.sh

app=${DHC_APP:?the image sets DHC_APP}
config=${DHC_CONFIG_DIR:-/opt/dhc/config}
environment=${DHC_ENV:-}
app_id=$(dhc_app_id "$app")

[ -f "$config/defaults.env" ] || dhc_fail "$config is not the configuration directory (no defaults.env)"
if [ -n "$environment" ] && [ ! -f "$config/env/$environment.env" ]; then
    dhc_fail "DHC_ENV is '$environment', but there is no $config/env/$environment.env"
fi

heap=$(dhc_setting "$config" "$app_id" "$environment" DHC_JVM_HEAP)
options=$(dhc_setting "$config" "$app_id" "$environment" DHC_JVM_OPTS)
jvm="${heap:+-Xmx$heap}${options:+ $options}"

if [ "$(dhc_app_kind "$app")" = java ]; then
    # The start script of the distribution adds the mandatory JVM flags itself.
    JAVA_OPTS="${JAVA_OPTS:-}${jvm:+ $jvm}"
    export JAVA_OPTS
    exec "/opt/dhc/app/bin/$app" "$@"
else
    cd /opt/dhc/app
    # jvm.args holds the mandatory JVM flags. The word splitting of $jvm is intended.
    # shellcheck disable=SC2086
    exec java @jvm.args $jvm -jar "$app.jar" "$@"
fi
