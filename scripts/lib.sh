# Shared by run.sh, demo.sh, smoke.sh and check_env.sh, and by the container entry point.
# Sourced, never run. Runs on macOS bash 3.2, Linux and Windows Git Bash: no associative arrays,
# no GNU-only flags.
#
# Settings are read here the way the applications read them (config/defaults.env has the rules):
# lowest first defaults.env, apps/<app id>.env, env/<environment>.env, the process environment;
# and in the environment and in an environment file, a key scoped to the application
# (DHC_DHC_B_HTTP_PORT) wins over the plain one.

DHC_LIB_NAME=${DHC_LIB_NAME:-dhc}

dhc_fail() {
    echo "$DHC_LIB_NAME: $1" >&2
    exit 1
}

dhc_say() {
    echo "$DHC_LIB_NAME: $1"
}

# The repository root: the directory above scripts/.
dhc_root() {
    (cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
}

# dhc_file_value <file> <key>: the value of KEY in a KEY=value file; nothing if it is not there.
dhc_file_value() {
    [ -f "$1" ] || return 0
    # The last line that sets the key wins; a carriage return from a Windows checkout is dropped.
    sed -n "s/^$2=//p" "$1" | tail -n 1 | tr -d '\r'
}

# dhc_scoped_key <app id> <key>: DHC_HTTP_PORT for dhc-b is DHC_DHC_B_HTTP_PORT.
dhc_scoped_key() {
    echo "DHC_$(echo "$1" | tr 'a-z-' 'A-Z_')_${2#DHC_}"
}

# dhc_env_value <name>: the value of an environment variable by name; nothing if unset.
dhc_env_value() {
    eval "printf '%s' \"\${$1:-}\""
}

# dhc_setting <config dir> <app id> <environment> <key>: the value the application would use.
dhc_setting() {
    dhc_s_dir=$1
    dhc_s_app=$2
    dhc_s_env=$3
    dhc_s_key=$4
    dhc_s_scoped=$(dhc_scoped_key "$dhc_s_app" "$dhc_s_key")
    for dhc_s_name in "$dhc_s_scoped" "$dhc_s_key"; do
        dhc_s_value=$(dhc_env_value "$dhc_s_name")
        if [ -n "$dhc_s_value" ]; then
            echo "$dhc_s_value"
            return 0
        fi
    done
    for dhc_s_name in "$dhc_s_scoped" "$dhc_s_key"; do
        dhc_s_value=$(dhc_file_value "$dhc_s_dir/env/$dhc_s_env.env" "$dhc_s_name")
        if [ -n "$dhc_s_value" ]; then
            echo "$dhc_s_value"
            return 0
        fi
    done
    for dhc_s_file in "$dhc_s_dir/apps/$dhc_s_app.env" "$dhc_s_dir/defaults.env"; do
        dhc_s_value=$(dhc_file_value "$dhc_s_file" "$dhc_s_key")
        if [ -n "$dhc_s_value" ]; then
            echo "$dhc_s_value"
            return 0
        fi
    done
    return 0
}

# The applications of this repository. A host is <app id>-<kind>.
DHC_APPS="dhc-a-java dhc-a-spring dhc-b-java dhc-b-spring"

# dhc_app_id <app>: dhc-b-spring is the application dhc-b.
dhc_app_id() {
    case "$1" in
        *-java) echo "${1%-java}" ;;
        *-spring) echo "${1%-spring}" ;;
        *) dhc_fail "unknown application '$1'. Applications: $DHC_APPS" ;;
    esac
}

# dhc_app_kind <app>: java (a plain Java host) or spring.
dhc_app_kind() {
    case "$1" in
        dhc-a-java | dhc-b-java) echo java ;;
        dhc-a-spring | dhc-b-spring) echo spring ;;
        *) dhc_fail "unknown application '$1'. Applications: $DHC_APPS" ;;
    esac
}

# dhc_pair_apps <pair>: the two hosts of a pair, A first.
dhc_pair_apps() {
    case "$1" in
        primary) echo "dhc-a-java dhc-b-spring" ;;
        secondary) echo "dhc-a-spring dhc-b-java" ;;
        *) dhc_fail "unknown pair '$1'. Pairs: primary (dhc-a-java + dhc-b-spring), secondary (dhc-a-spring + dhc-b-java)" ;;
    esac
}

# dhc_app_home <root> <app>: where the built application is.
dhc_app_home() {
    if [ "$(dhc_app_kind "$2")" = java ]; then
        echo "$1/apps/$2/build/install/$2"
    else
        echo "$1/apps/$2/build/extracted"
    fi
}

# dhc_app_built <root> <app>: succeeds if the application has been built.
dhc_app_built() {
    if [ "$(dhc_app_kind "$2")" = java ]; then
        [ -f "$(dhc_app_home "$1" "$2")/bin/$2" ]
    else
        [ -f "$(dhc_app_home "$1" "$2")/$2.jar" ]
    fi
}

# dhc_build_command <app>: the Gradle command that builds what dhc_app_home points at.
dhc_build_command() {
    if [ "$(dhc_app_kind "$1")" = java ]; then
        echo "./gradlew :apps:$1:installDist"
    else
        echo "./gradlew :apps:$1:extractBootJar"
    fi
}

# dhc_health_url <config dir> <app> <environment>: the address that answers 200 once the
# application can do what it is for.
dhc_health_url() {
    dhc_h_app_id=$(dhc_app_id "$2")
    if [ "$(dhc_app_kind "$2")" = java ]; then
        # A plain Java host has Deephaven's port only. Its web IDE answers once the server is up.
        echo "http://localhost:$(dhc_setting "$1" "$dhc_h_app_id" "$3" DHC_HTTP_PORT)/ide/"
    else
        echo "http://localhost:$(dhc_setting "$1" "$dhc_h_app_id" "$3" DHC_WEB_PORT)/actuator/health/readiness"
    fi
}

# dhc_wait_for <url> <seconds> [pid]: waits until the address answers 200. Returns 2 as soon as
# the process with that pid is gone, 1 when the time is up.
dhc_wait_for() {
    dhc_w_waited=0
    while [ "$dhc_w_waited" -lt "$2" ]; do
        if curl -s -f -o /dev/null --max-time 5 "$1"; then
            return 0
        fi
        if [ -n "${3:-}" ] && ! kill -0 "$3" 2> /dev/null; then
            return 2
        fi
        sleep 1
        dhc_w_waited=$((dhc_w_waited + 1))
    done
    return 1
}

# dhc_stop_process <pid> [seconds]: asks a process this shell (or an earlier run of a script)
# started to stop, and waits. Returns 0 if it ended by itself, 1 if it had to be ended.
#
# In Windows Git Bash the process id is the shell's own number for a Windows process, and a
# plain kill ends that process without a word to its children. So the Windows process id is
# read first, and afterwards whatever is left of that process tree is ended with taskkill.
# Nothing there can be asked to stop; the application is simply ended.
dhc_stop_process() {
    dhc_k_pid=$1
    dhc_k_limit=${2:-40}
    dhc_k_windows=
    if [ -r "/proc/$dhc_k_pid/winpid" ]; then
        dhc_k_windows=$(cat "/proc/$dhc_k_pid/winpid" 2> /dev/null || true)
    fi
    dhc_k_status=0
    kill "$dhc_k_pid" 2> /dev/null || true
    dhc_k_waited=0
    while kill -0 "$dhc_k_pid" 2> /dev/null && [ "$dhc_k_waited" -lt "$dhc_k_limit" ]; do
        sleep 1
        dhc_k_waited=$((dhc_k_waited + 1))
    done
    if kill -0 "$dhc_k_pid" 2> /dev/null; then
        kill -9 "$dhc_k_pid" 2> /dev/null || true
        dhc_k_status=1
    fi
    if [ -n "$dhc_k_windows" ] && command -v taskkill > /dev/null 2>&1; then
        # //F //T: forcibly, with its children. The doubled slashes keep Git Bash from reading
        # the options as paths.
        taskkill //F //T //PID "$dhc_k_windows" > /dev/null 2>&1 || true
    fi
    return "$dhc_k_status"
}

# dhc_wait_until_silent <url> <seconds>: waits until nothing answers at the address any more.
dhc_wait_until_silent() {
    dhc_q_waited=0
    while [ "$dhc_q_waited" -lt "$2" ]; do
        if ! curl -s -o /dev/null --max-time 3 "$1"; then
            return 0
        fi
        sleep 1
        dhc_q_waited=$((dhc_q_waited + 1))
    done
    return 1
}

# dhc_java: the java command, from JAVA_HOME if it is set.
dhc_java() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        echo "$JAVA_HOME/bin/java"
    else
        echo java
    fi
}

# dhc_without_keys: a filter for logs. Deephaven prints the key it uses at start, and a key has
# no place on a terminal that is being shared or in a build log.
dhc_without_keys() {
    grep -v -i -e "psk" -e "pre-shared key" || true
}
