#!/usr/bin/env bash
# Starts the backend (throwaway PostgreSQL + demo data) and the frontend dev server together.
# Ctrl+C stops both. Uses the local JDK if there is one, otherwise Maven in Docker.
#   BACKEND_PORT=8081 FRONTEND_PORT=5174 ./scripts/dev.sh   # if the default ports are taken
set -euo pipefail
cd "$(dirname "$0")/.."

BACKEND_PORT="${BACKEND_PORT:-8080}"
FRONTEND_PORT="${FRONTEND_PORT:-5173}"
trap 'trap - EXIT INT TERM; kill 0 2>/dev/null' EXIT INT TERM

if command -v java >/dev/null 2>&1; then
  MVN=./mvnw
else
  MVN=./scripts/mvn-docker.sh
fi

echo "Starting backend on :${BACKEND_PORT} (${MVN}) ..."
"$MVN" -q spring-boot:test-run -Dspring-boot.run.arguments=--server.port="${BACKEND_PORT}" &

[ -d frontend/node_modules ] || npm --prefix frontend ci
echo "Starting frontend on :${FRONTEND_PORT} ..."
BACKEND_URL="http://localhost:${BACKEND_PORT}" npm --prefix frontend run dev -- --port "${FRONTEND_PORT}" &

wait -n
