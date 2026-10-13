#!/bin/sh
# Nightly logical backup of the edge node database (custom format, compressed), with retention.
# Complements continuous WAL archiving (point-in-time recovery). Restore: scripts/restore.sh.
set -eu
dir="${1:?usage: backup.sh <backup-dir>}"
keep="${BACKUP_RETENTION_DAYS:-14}"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
file="$dir/storefront-$stamp.dump"
pg_dump --format=custom --compress=6 --file="$file.partial"
pg_restore --list "$file.partial" > /dev/null   # verify the archive is readable before keeping it
mv "$file.partial" "$file"
find "$dir" -name 'storefront-*.dump' -mtime +"$keep" -delete
echo "backup ok: $file"
