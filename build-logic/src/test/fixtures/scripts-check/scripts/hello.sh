#!/usr/bin/env bash
# Says hello, a few portable ways.

set -eu
. "$(dirname "$0")/lib.sh"

start_timeout=${START_TIMEOUT:-5}
began=$(date +%s)
name=$(sed -n 's/^NAME=//p' "$0" | tail -n 1)
if grep -v -i -e "x" "$0" > /dev/null 2>&1; then
    echo "hello $(upper "${name:-world}") after $(($(date +%s) - began))s, limit $start_timeout"
fi
