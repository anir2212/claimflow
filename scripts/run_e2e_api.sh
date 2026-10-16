#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
if [ ! -f target/claimflow-0.1.0.jar ]; then
  echo 'Build the API first: ./mvnw -DskipTests package' >&2
  exit 1
fi
if [ -n "${JAVA_HOME:-}" ]; then
  java_bin="$JAVA_HOME/bin/java"
else
  java_bin=java
fi
exec env SPRING_PROFILES_ACTIVE=default SERVER_ADDRESS=127.0.0.1 SERVER_PORT=8080 \
  "$java_bin" -Xms64m -Xmx512m -jar target/claimflow-0.1.0.jar
