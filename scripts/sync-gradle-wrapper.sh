#!/usr/bin/env bash
# Points the Gradle wrapper at the address in config/repos/repos.env, without running Gradle.
#
# The wrapper reads only gradle/wrapper/gradle-wrapper.properties, and it reads it before any
# build logic runs. So its download address is the one artifact source that has to be copied out
# of repos.env. "./gradlew wrapper" makes the same change, but it needs a Gradle that is already
# downloaded, which is exactly what is missing on a network that cannot reach the old address.
#
# Usage: scripts/sync-gradle-wrapper.sh        (bash scripts/sync-gradle-wrapper.sh on Windows)
#
# Runs on macOS bash 3.2, Linux and Windows Git Bash.

set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
repos="$root/config/repos/repos.env"
local_repos="$root/config/repos/repos.local.env"
catalog="$root/gradle/libs.versions.toml"
wrapper="$root/gradle/wrapper/gradle-wrapper.properties"
cr=$(printf '\r')

fail() {
    echo "sync-gradle-wrapper: $1" >&2
    exit 1
}

# Prints the value of the last "KEY=value" line for a key in a file, or nothing.
value_of() {
    found=""
    while IFS= read -r line || [ -n "$line" ]; do
        line=${line%"$cr"}
        case "$line" in
            "$1="*) found=${line#*=} ;;
        esac
    done < "$2"
    printf '%s' "$found"
}

[ -f "$repos" ] || fail "missing $repos"
[ -f "$catalog" ] || fail "missing $catalog"
[ -f "$wrapper" ] || fail "missing $wrapper"

# repos.local.env, when present, overrides repos.env key by key.
base_url=$(value_of DHC_GRADLE_DISTRIBUTIONS_URL "$repos")
if [ -f "$local_repos" ]; then
    local_url=$(value_of DHC_GRADLE_DISTRIBUTIONS_URL "$local_repos")
    if [ -n "$local_url" ]; then
        base_url=$local_url
    fi
fi
[ -n "$base_url" ] || fail "DHC_GRADLE_DISTRIBUTIONS_URL is not set in $repos"
case "$base_url" in
    https://* | http://*) ;;
    *) fail "DHC_GRADLE_DISTRIBUTIONS_URL must be an http(s) URL, but it is: $base_url" ;;
esac
host=${base_url#*://}
host=${host%%/*}
case "$host" in
    *@*) fail "DHC_GRADLE_DISTRIBUTIONS_URL must not contain a user name or password" ;;
esac
base_url=${base_url%/}

# The Gradle version: the line  gradle = "x.y.z"  in the version catalog.
version=""
while IFS= read -r line || [ -n "$line" ]; do
    line=${line%"$cr"}
    case "$line" in
        'gradle = "'*)
            version=${line#*\"}
            version=${version%%\"*}
            ;;
    esac
done < "$catalog"
[ -n "$version" ] || fail "no line  gradle = \"<version>\"  in $catalog"

url="$base_url/gradle-$version-bin.zip"
# A properties file escapes ':' in a value, the way Gradle itself writes this line.
escaped=$(printf '%s' "$url" | sed 's/:/\\:/g')

# Rewrite the one line; every other line is kept as it is.
content=""
replaced=no
while IFS= read -r line || [ -n "$line" ]; do
    line=${line%"$cr"}
    case "$line" in
        distributionUrl=*)
            line="distributionUrl=$escaped"
            replaced=yes
            ;;
    esac
    content="$content$line
"
done < "$wrapper"
[ "$replaced" = yes ] || fail "no distributionUrl line in $wrapper"

printf '%s' "$content" > "$wrapper"
echo "gradle/wrapper/gradle-wrapper.properties now downloads $url"
