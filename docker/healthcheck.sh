#!/usr/bin/env bash
# Health check of both images: is this process well? Exits 0 if so.
#
# A plain Java host: Deephaven's web IDE answers. A Spring host: /actuator/health/liveness,
# which includes the embedded Deephaven and not the upstream. An instance B whose A is away is
# still well, and restarting it would not bring A back.

set -eu
DHC_LIB_NAME=healthcheck
. /opt/dhc/lib.sh

app=${DHC_APP:?the image sets DHC_APP}
config=${DHC_CONFIG_DIR:-/opt/dhc/config}
environment=${DHC_ENV:-}
app_id=$(dhc_app_id "$app")

if [ "$(dhc_app_kind "$app")" = java ]; then
    url="http://localhost:$(dhc_setting "$config" "$app_id" "$environment" DHC_HTTP_PORT)/ide/"
else
    url="http://localhost:$(dhc_setting "$config" "$app_id" "$environment" DHC_WEB_PORT)/actuator/health/liveness"
fi
exec curl -s -f -o /dev/null --max-time 4 "$url"
