#!/usr/bin/env bash
# Checks a running pair of instances from outside: both answer, and what B holds is what A has.
#
# Usage: scripts/smoke.sh [pair]       pair: primary or secondary; the one demo.sh started
#                                      unless given
#        (bash scripts/smoke.sh ... on Windows)
#
# The environment is DHC_ENV (dev unless set). It works the same against scripts/demo.sh up and
# against the containers of docker/compose.yaml: it only needs the ports.
#
# What it compares. A's feed does not stop, so "equal" is asked like this: read A, read B, read
# A again. B's orders, and the quantity filled, have to lie between A's two readings. They only
# ever grow, so B is then exactly what A was at some moment in between. That is tried a few
# times, because B is allowed to be a moment behind.
#
# Build first: ./gradlew :apps:dhc-probe:installDist
# Needs curl. In an environment with keys, the keys the instances were started with have to be
# in the environment of this script too. Runs on macOS bash 3.2, Linux and Windows Git Bash.

set -eu
DHC_LIB_NAME=smoke
. "$(dirname "$0")/lib.sh"

root=$(dhc_root)
config=${DHC_CONFIG_DIR:-$root/config}
environment=${DHC_ENV:-dev}
wait_seconds=${DHC_SMOKE_WAIT:-120}
attempts=${DHC_SMOKE_ATTEMPTS:-10}

if [ $# -ge 1 ]; then
    pair=$1
elif [ -f "$root/build/demo/$environment/pair" ]; then
    pair=$(cat "$root/build/demo/$environment/pair")
else
    pair=primary
fi
# shellcheck disable=SC2046
set -- $(dhc_pair_apps "$pair")
app_a=$1
app_b=$2

probe="$root/apps/dhc-probe/build/install/dhc-probe/bin/dhc-probe"
[ -f "$probe" ] || dhc_fail "the probe is not built. Run: ./gradlew :apps:dhc-probe:installDist"
command -v curl > /dev/null 2>&1 || dhc_fail "curl is not on the PATH"
[ -f "$config/env/$environment.env" ] || dhc_fail "there is no environment '$environment': no $config/env/$environment.env"

port_a=$(dhc_setting "$config" dhc-a "$environment" DHC_HTTP_PORT)
port_b=$(dhc_setting "$config" dhc-b "$environment" DHC_HTTP_PORT)
key_a=$(dhc_setting "$config" dhc-a "$environment" DHC_AUTH_PSK)
key_b=$(dhc_setting "$config" dhc-b "$environment" DHC_AUTH_PSK)

dhc_say "environment '$environment', $pair pair: $app_a and $app_b"

# ---- 1. both answer --------------------------------------------------------------------------

for app in $app_a $app_b; do
    url=$(dhc_health_url "$config" "$app" "$environment")
    dhc_wait_for "$url" "$wait_seconds" || dhc_fail "$app does not answer at $url (waited $wait_seconds seconds)"
    echo "  ok  $app answers at $url"
done

# ---- 2. B holds what A has -------------------------------------------------------------------

# read_table <port> <key> <table>: sets read_rows and read_sum from the probe's two lines.
read_table() {
    if ! read_output=$(DHC_PROBE_PSK="$2" "$probe" "dh+plain://localhost:$1" "$3" --sum CumQty); then
        dhc_fail "the probe could not read table '$3' on port $1"
    fi
    read_rows=$(echo "$read_output" | sed -n 's/^rows=//p' | tr -d '\r')
    read_sum=$(echo "$read_output" | sed -n 's/^sum\.CumQty=//p' | tr -d '\r')
    if [ -z "$read_rows" ] || [ -z "$read_sum" ]; then
        dhc_fail "the probe's answer for table '$3' on port $1 is not understood: $read_output"
    fi
}

matched=no
attempt=1
while [ "$attempt" -le "$attempts" ]; do
    read_table "$port_a" "$key_a" orders
    a1_rows=$read_rows
    a1_sum=$read_sum
    read_table "$port_b" "$key_b" orders_a
    b_rows=$read_rows
    b_sum=$read_sum
    read_table "$port_b" "$key_b" cum_by_symbol
    b_symbols=$read_rows
    b_symbol_sum=$read_sum
    read_table "$port_a" "$key_a" orders
    a2_rows=$read_rows
    a2_sum=$read_sum
    if [ "$a1_rows" -gt 0 ] \
        && [ "$b_rows" -ge "$a1_rows" ] && [ "$b_rows" -le "$a2_rows" ] \
        && [ "$b_sum" -ge "$a1_sum" ] && [ "$b_sum" -le "$a2_sum" ] \
        && [ "$b_symbol_sum" -ge "$a1_sum" ] && [ "$b_symbol_sum" -le "$a2_sum" ]; then
        matched=yes
        break
    fi
    sleep 2
    attempt=$((attempt + 1))
done

if [ "$matched" != yes ]; then
    echo "  A orders:          $a1_rows rows, CumQty $a1_sum, then $a2_rows rows, CumQty $a2_sum" >&2
    echo "  B orders_a:        $b_rows rows, CumQty $b_sum" >&2
    echo "  B cum_by_symbol:   $b_symbols symbols, CumQty $b_symbol_sum" >&2
    dhc_fail "B's totals are not between two readings of A's, in $attempts attempts"
fi
echo "  ok  B orders_a has $b_rows orders, CumQty $b_sum; A had $a1_rows and $a1_sum before, $a2_rows and $a2_sum after"
echo "  ok  B cum_by_symbol adds up to $b_symbol_sum over $b_symbols symbols"

# ---- 3. what a Spring host adds --------------------------------------------------------------

if [ "$(dhc_app_kind "$app_b")" = spring ]; then
    web_b=$(dhc_setting "$config" dhc-b "$environment" DHC_WEB_PORT)
    summary=$(curl -s -f --max-time 10 "http://localhost:$web_b/api/summary") \
        || dhc_fail "$app_b does not answer at http://localhost:$web_b/api/summary"
    case "$summary" in
        *'"state":"CONNECTED"'*'"stale":false'*) echo "  ok  $app_b /api/summary: feed CONNECTED, not stale" ;;
        *) dhc_fail "$app_b /api/summary does not say the feed is connected: $summary" ;;
    esac
    curl -s -f -o /dev/null --max-time 10 "http://localhost:$web_b/" \
        || dhc_fail "$app_b does not serve its page at http://localhost:$web_b/"
    echo "  ok  $app_b serves its page"
fi
if [ "$(dhc_app_kind "$app_a")" = spring ]; then
    web_a=$(dhc_setting "$config" dhc-a "$environment" DHC_WEB_PORT)
    summary=$(curl -s -f --max-time 10 "http://localhost:$web_a/api/summary") \
        || dhc_fail "$app_a does not answer at http://localhost:$web_a/api/summary"
    case "$summary" in
        *'"orders":'*) echo "  ok  $app_a /api/summary answers from its order cache" ;;
        *) dhc_fail "$app_a /api/summary is not understood: $summary" ;;
    esac
fi

dhc_say "passed"
