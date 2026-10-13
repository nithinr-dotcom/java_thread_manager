#!/usr/bin/env bash
# Runs Maven inside a JDK 21 container for machines without a local JDK.
# The Docker socket is mounted so Testcontainers can start PostgreSQL next to it.
set -euo pipefail
cd "$(dirname "$0")/.."
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
