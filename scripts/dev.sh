#!/usr/bin/env bash
# Starts the backend (throwaway PostgreSQL + demo data) and the frontend dev server together.
# The UI signs in by itself with the demo manager key and opens on the scenario tests.
# Ctrl+C stops both. Uses the local JDK if there is one, otherwise Maven in Docker (Linux only).
# Works with the bash 3.2 that ships with macOS. Only Docker installed? Use `docker compose up --build`.
#   BACKEND_PORT=8081 FRONTEND_PORT=5174 ./scripts/dev.sh   # if the default ports are taken
set -euo pipefail
cd "$(dirname "$0")/.."

BACKEND_PORT="${BACKEND_PORT:-8080}"
FRONTEND_PORT="${FRONTEND_PORT:-5173}"
trap 'trap - EXIT INT TERM; kill 0 2>/dev/null' EXIT INT TERM

if command -v java >/dev/null 2>&1; then
  MVN=./mvnw
elif [ "$(uname -s)" = Linux ]; then
  MVN=./scripts/mvn-docker.sh
else
  echo "No JDK 21 found. Install one, or run the whole stack with: docker compose up --build" >&2
  exit 1
fi

echo "Starting backend on :${BACKEND_PORT} (${MVN}) ..."
"$MVN" -q spring-boot:test-run -Dspring-boot.run.arguments=--server.port="${BACKEND_PORT}" &
BACKEND_PID=$!

[ -d frontend/node_modules ] || npm --prefix frontend ci
echo "Starting frontend on :${FRONTEND_PORT} ..."
BACKEND_URL="http://localhost:${BACKEND_PORT}" VITE_DEMO_API_KEY=sf_demo_manager npm --prefix frontend run dev -- --port "${FRONTEND_PORT}" &
FRONTEND_PID=$!

# Stop everything as soon as either side exits (`wait -n` would need bash 4.3+).
while kill -0 "$BACKEND_PID" 2>/dev/null && kill -0 "$FRONTEND_PID" 2>/dev/null; do
  sleep 1
done
