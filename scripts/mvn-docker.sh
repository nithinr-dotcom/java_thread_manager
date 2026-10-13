#!/usr/bin/env bash
# Runs Maven inside a JDK 21 container for machines without a local JDK.
# The Docker socket is mounted so Testcontainers can start PostgreSQL next to it.
# Linux only: it relies on host networking, which Docker Desktop (macOS/Windows) does not enable by default.
set -euo pipefail
cd "$(dirname "$0")/.."
if [ "$(uname -s)" != Linux ]; then
  echo "scripts/mvn-docker.sh needs Linux. Install JDK 21 and use ./mvnw, or run the app with: docker compose up --build" >&2
  exit 1
fi
mkdir -p "${HOME}/.m2"
exec docker run --rm \
  --network host \
  -u "$(id -u):$(id -g)" \
  --group-add "$(stat -c %g /var/run/docker.sock)" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=localhost \
  -e MAVEN_CONFIG=/var/maven/.m2 \
  -e HOME=/var/maven \
  -v "${HOME}/.m2":/var/maven/.m2 \
  -v "$PWD":/workspace -w /workspace \
  maven:3.9-eclipse-temurin-21 mvn -Duser.home=/var/maven "$@"
