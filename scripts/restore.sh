#!/bin/sh
# Restores a logical backup into an EMPTY database. Stop the node first (no checkouts during restore).
#   PGHOST=... PGUSER=... PGDATABASE=storefront scripts/restore.sh /backups/storefront-<stamp>.dump
# After restoring, start the node: Flyway validates the schema, the outbox re-delivers anything that
# was not yet published (consumers are idempotent). Then run GET /api/v1/reports/fiscal-chain for the
# affected days.
set -eu
file="${1:?usage: restore.sh <dump-file>}"
pg_restore --exit-on-error --no-owner --single-transaction --dbname="$PGDATABASE" "$file"
echo "restore ok: $file"
