#!/usr/bin/env bash
# Starts instance A and then instance B on this machine, in the background, and stops them.
#
# Usage: scripts/demo.sh up [pair]     start A, wait for it, start B, wait for it
#        scripts/demo.sh down [app]    stop B, then A; or only the one named, and wait until it
#                                      is gone (stop A alone to watch B live through it)
#        scripts/demo.sh status        which of the two run and answer
#        scripts/demo.sh logs [app]    the last lines of their logs
#        (bash scripts/demo.sh ... on Windows)
#
#   pair  primary (the default): dhc-a-java and dhc-b-spring
#         secondary:             dhc-a-spring and dhc-b-java
#
# The environment is DHC_ENV (dev unless set): DHC_ENV=uat scripts/demo.sh up
# One pair runs per environment. Logs and process ids are in build/demo/<environment>/.
#
# Build first: ./gradlew installDist extractBootJar
# Needs curl. Runs on macOS bash 3.2, Linux and Windows Git Bash.

set -eu
DHC_LIB_NAME=demo
. "$(dirname "$0")/lib.sh"

command=${1:-}
root=$(dhc_root)
config=${DHC_CONFIG_DIR:-$root/config}
environment=${DHC_ENV:-dev}
state="$root/build/demo/$environment"
start_timeout=${DHC_DEMO_START_TIMEOUT:-180}

[ -f "$config/env/$environment.env" ] || dhc_fail "there is no environment '$environment': no $config/env/$environment.env"

pid_of() {
    if [ -f "$state/$1.pid" ]; then
        cat "$state/$1.pid"
    fi
}

is_running() {
    is_running_pid=$(pid_of "$1")
    [ -n "$is_running_pid" ] && kill -0 "$is_running_pid" 2> /dev/null
}

forget() {
    rm -f "${state:?}/${1:?}.pid"
}

show_log() {
    echo "---- last lines of $state/$1.log ----" >&2
    tail -n 40 "$state/$1.log" 2> /dev/null | dhc_without_keys >&2
}

# In an environment with keys, both instances need theirs before they start: B has to present
# A's key, so A cannot be left to make one up.
require_keys() {
    a_mode=$(dhc_setting "$config" dhc-a "$environment" DHC_AUTH_MODE)
    b_mode=$(dhc_setting "$config" dhc-b "$environment" DHC_AUTH_MODE)
    if [ "$a_mode" != anonymous ]; then
        [ -n "$(dhc_setting "$config" dhc-a "$environment" DHC_AUTH_PSK)" ] \
            || dhc_fail "environment '$environment' asks for keys. Set DHC_DHC_A_AUTH_PSK to the key instance A should ask for."
        [ -n "$(dhc_setting "$config" dhc-b "$environment" DHC_UPSTREAM_DHC_A_PSK)" ] \
            || dhc_fail "environment '$environment' asks for keys. Set DHC_UPSTREAM_DHC_A_PSK to A's key, for B to present."
    fi
    if [ "$b_mode" != anonymous ]; then
        [ -n "$(dhc_setting "$config" dhc-b "$environment" DHC_AUTH_PSK)" ] \
            || dhc_fail "environment '$environment' asks for keys. Set DHC_DHC_B_AUTH_PSK to the key instance B should ask for."
    fi
}

start() {
    start_app=$1
    if is_running "$start_app"; then
        dhc_say "$start_app is already running (pid $(pid_of "$start_app"))"
        return 0
    fi
    url=$(dhc_health_url "$config" "$start_app" "$environment")
    # Nothing may answer there yet: an application left from another run would pass for this one.
    if curl -s -o /dev/null --max-time 5 "$url"; then
        dhc_fail "something already answers at $url. Stop it first; scripts/demo.sh status shows what this script started."
    fi
    DHC_ENV="$environment" DHC_CONFIG_DIR="$config" DHC_WORK_DIR="$state/$start_app-work" \
        nohup bash "$root/scripts/run.sh" "$start_app" "$environment" > "$state/$start_app.log" 2>&1 &
    start_pid=$!
    echo "$start_pid" > "$state/$start_app.pid"
    dhc_say "started $start_app (pid $start_pid), waiting for $url"
    wait_status=0
    dhc_wait_for "$url" "$start_timeout" "$start_pid" || wait_status=$?
    if [ "$wait_status" -ne 0 ]; then
        show_log "$start_app"
        if [ "$wait_status" -eq 2 ]; then
            forget "$start_app"
            dhc_fail "$start_app exited before $url answered"
        fi
        dhc_fail "$start_app did not answer at $url within $start_timeout seconds. It is still running; scripts/demo.sh down stops it."
    fi
    dhc_say "$start_app is up"
}

stop() {
    stop_app=$1
    stop_pid=$(pid_of "$stop_app")
    if [ -z "$stop_pid" ]; then
        return 0
    fi
    stop_url=$(dhc_health_url "$config" "$stop_app" "$environment")
    if kill -0 "$stop_pid" 2> /dev/null; then
        stop_began=$(date +%s)
        if dhc_stop_process "$stop_pid" 40; then
            dhc_say "stopped $stop_app after about $(($(date +%s) - stop_began))s"
        else
            dhc_say "$stop_app did not stop within 40 seconds and was ended"
        fi
    else
        dhc_say "$stop_app was not running"
    fi
    forget "$stop_app"
    # The port has to be free again, or the next start would mistake what is left for itself.
    if ! dhc_wait_until_silent "$stop_url" 30; then
        dhc_fail "$stop_app was stopped, but something still answers at $stop_url"
    fi
}

current_pair() {
    if [ -f "$state/pair" ]; then
        cat "$state/pair"
    else
        echo primary
    fi
}

case "$command" in
    up)
        pair=${2:-primary}
        apps=$(dhc_pair_apps "$pair")
        command -v curl > /dev/null 2>&1 || dhc_fail "curl is not on the PATH"
        for app in $apps; do
            dhc_app_built "$root" "$app" || dhc_fail "$app is not built. Run: $(dhc_build_command "$app")"
        done
        mkdir -p "$state"
        if [ -f "$state/pair" ] && [ "$(cat "$state/pair")" != "$pair" ]; then
            for app in $(dhc_pair_apps "$(cat "$state/pair")"); do
                if is_running "$app"; then
                    dhc_fail "the $(cat "$state/pair") pair is running in '$environment'. Run scripts/demo.sh down first."
                fi
            done
        fi
        require_keys
        echo "$pair" > "$state/pair"
        for app in $apps; do
            start "$app"
        done
        dhc_say "the $pair pair is up in '$environment'. scripts/smoke.sh checks it; scripts/demo.sh down stops it."
        for app in $apps; do
            app_id=$(dhc_app_id "$app")
            echo "  $app: Deephaven http://localhost:$(dhc_setting "$config" "$app_id" "$environment" DHC_HTTP_PORT)/ide/"
            if [ "$(dhc_app_kind "$app")" = spring ]; then
                web="http://localhost:$(dhc_setting "$config" "$app_id" "$environment" DHC_WEB_PORT)"
                if [ "$app_id" = dhc-b ]; then
                    echo "  $app: its own page $web/"
                else
                    echo "  $app: its REST API $web/api/summary"
                fi
            fi
        done
        ;;
    down)
        only=${2:-}
        # shellcheck disable=SC2046
        set -- $(dhc_pair_apps "$(current_pair)")
        if [ -n "$only" ]; then
            if [ "$only" != "$1" ] && [ "$only" != "$2" ]; then
                dhc_fail "$only is not one of the $(current_pair) pair in '$environment' ($1, $2)"
            fi
            stop "$only"
            dhc_say "scripts/demo.sh up starts it again and leaves the other one running"
        else
            # B first: it then sees no outage, and A is not asked for anything while it stops.
            stop "$2"
            stop "$1"
        fi
        ;;
    status)
        pair=$(current_pair)
        dhc_say "environment '$environment', $pair pair"
        for app in $(dhc_pair_apps "$pair"); do
            url=$(dhc_health_url "$config" "$app" "$environment")
            if is_running "$app"; then
                if curl -s -f -o /dev/null --max-time 5 "$url"; then
                    echo "  $app: running (pid $(pid_of "$app")), answers at $url"
                else
                    echo "  $app: running (pid $(pid_of "$app")), does NOT answer at $url"
                fi
            else
                echo "  $app: not running"
            fi
        done
        ;;
    logs)
        if [ $# -ge 2 ]; then
            apps=$2
        else
            apps=$(dhc_pair_apps "$(current_pair)")
        fi
        for app in $apps; do
            [ -f "$state/$app.log" ] || dhc_fail "no log for $app in $state"
            echo "---- $state/$app.log ----"
            tail -n 60 "$state/$app.log" | dhc_without_keys
        done
        ;;
    *)
        dhc_fail "usage: scripts/demo.sh up [primary|secondary] | down [app] | status | logs [app]"
        ;;
esac
