#!/usr/bin/env bash
# Starts an installed plain Java host from its start script, waits until its web IDE answers,
# and stops it again. Proves that the distribution starts on this machine and shell.
#
# Usage: scripts/check-start.sh <app> [port]        for example: scripts/check-start.sh dhc-a-java
#        (bash scripts/check-start.sh ... on Windows)
#
# Build the distribution first: ./gradlew :apps:<app>:installDist
# Needs curl. Runs on macOS bash 3.2, Linux and Windows Git Bash.

set -eu
DHC_LIB_NAME=check-start
. "$(dirname "$0")/lib.sh"

fail() {
    dhc_fail "$1"
}

[ $# -ge 1 ] || fail "usage: scripts/check-start.sh <app> [port]"
app=$1
port=${2:-10000}
timeout_seconds=180

root=$(cd "$(dirname "$0")/.." && pwd)
start="$root/apps/$app/build/install/$app/bin/$app"
[ -f "$start" ] || fail "no $start. Run ./gradlew :apps:$app:installDist first."
command -v curl > /dev/null 2>&1 || fail "curl is not on the PATH"

work=$(mktemp -d "${TMPDIR:-/tmp}/dhc-check-start.XXXXXX")
log="$work/app.log"

# The instance gets a directory of its own and the port asked for. Everything else is the
# default a user of the distribution gets, key-protected access included.
DHC_HTTP_PORT="$port" DHC_WORK_DIR="$work/work" "$start" > "$log" 2>&1 &
pid=$!

stop() {
    dhc_stop_process "$pid" 40 || true
    wait "$pid" 2> /dev/null || true
    # Leave the port free for whatever runs next.
    dhc_wait_until_silent "http://localhost:$port/ide/" 30 || true
}
trap stop EXIT

# Deephaven logs the access key it made up. The key dies with the process, but it has no place
# in a build log either.
show_log() {
    echo "---- last lines of $log ----" >&2
    grep -v -i -e "psk" -e "pre-shared key" "$log" | tail -n 60 >&2 || true
}

url="http://localhost:$port/ide/"
waited=0
up=no
while [ "$waited" -lt "$timeout_seconds" ]; do
    if curl -s -f -o /dev/null "$url"; then
        up=yes
        break
    fi
    if ! kill -0 "$pid" 2> /dev/null; then
        show_log
        fail "$app exited before $url answered"
    fi
    sleep 1
    waited=$((waited + 1))
done

if [ "$up" != yes ]; then
    show_log
    fail "$url did not answer within $timeout_seconds seconds"
fi

# The web IDE is static content; this line is written once the application modules have run.
if ! grep -q "is up:" "$log"; then
    show_log
    fail "$url answers, but the log has no 'is up:' line"
fi

echo "check-start: $app started from its start script and $url answered after about ${waited}s"
