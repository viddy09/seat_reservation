#!/usr/bin/env sh
# Fire a concurrent stampede at the seat service and verify every invariant.
#
#   ./burst.sh <BASE_URL> [--concurrency N] [--total N] [--seats N] [--hot-seats N] [--storm-size N] [--users N]
#
# Needs ADMIN_API_KEY in the environment (or --admin-key). Exit status: 0 pass, 1 a check failed, 2 setup error.
# Works with a JDK 21 on the PATH, or with only Docker installed.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
jar="$here/burst/target/burst.jar"
build="./mvnw -B -q -pl burst -am package -DskipTests -Dcheckstyle.skip=true -Dspotless.check.skip=true"

if command -v java >/dev/null 2>&1; then
    if [ ! -f "$jar" ] || [ "${BURST_REBUILD:-0}" = "1" ]; then
        (cd "$here" && sh -c "$build")
    fi
    exec java -jar "$jar" "$@"
fi

# Docker only. Inside the container "localhost" is the container itself: on Linux --network host makes it the host;
# on Docker Desktop (macOS/Windows) use http://host.docker.internal:8080 as the base URL instead.
exec docker run --rm --network host \
    -e ADMIN_API_KEY="${ADMIN_API_KEY:-}" \
    -v "$here":/src -w /src \
    eclipse-temurin:21.0.12_8-jdk-noble \
    sh -c "$build && java -jar burst/target/burst.jar \"\$@\"" burst "$@"
