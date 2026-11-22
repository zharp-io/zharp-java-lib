#!/usr/bin/env bash
# Builds everything the end-to-end run needs, then runs it.
#
#   ZHARP_E2E_TOKEN_FILE=/path/to/token.txt e2e/build.sh [scenario…]
#
# 1. Zharp's artifacts into build/e2e-repo (a Maven repository on disk; nothing is published).
# 2. The Maven plugin, with Maven (the wrapper).
# 3. The test apps that don't build during the run (the Gradle- and Maven-plugin apps build in it).
# 4. Postgres in Docker on port 15432, if it isn't running.
# 5. e2e/run.mjs against the local backend (ZHARP_E2E_API, default http://localhost:3000).
set -euo pipefail
cd "$(dirname "$0")/.."
root=$(pwd)

if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.home = //p')
  export JAVA_HOME
fi

./gradlew publishAllPublicationsToE2eRepository assemble -q
(cd zharp-maven-plugin && ./mvnw -q -B deploy -DaltDeploymentRepository="e2e::file:$root/build/e2e-repo")
(cd e2e/apps && ./gradlew :boot3-starter:bootJar :boot3-webflux:bootJar :servlet-jetty:installDist :ktor:installDist :library-mode:installDist -q --refresh-dependencies)

if ! docker ps --format '{{.Names}}' | grep -q '^zharp-java-e2e-pg$'; then
  docker rm -f zharp-java-e2e-pg >/dev/null 2>&1 || true
  docker run -d --name zharp-java-e2e-pg -e POSTGRES_USER=e2e -e POSTGRES_PASSWORD=e2e -e POSTGRES_DB=e2e -p 15432:5432 postgres:16-alpine >/dev/null
  sleep 5
fi

node e2e/run.mjs "$@"
