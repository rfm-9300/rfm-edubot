#!/usr/bin/env bash
set -euo pipefail

# Restores a Mongo archive produced by backup-mongo.sh. DESTRUCTIVE: uses
# --drop, which replaces existing collections with the ones in the archive.
#
# Usage: ./restore-mongo.sh path/to/mongo-YYYYMMDDThhmmssZ.archive.gz
#
# Run on the VPS with the app stack up (mongo service running).

APP_DIR="${APP_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"

archive="${1:?Usage: restore-mongo.sh <path-to-archive.gz>}"
if [[ ! -f "$archive" ]]; then
  echo "No such file: $archive" >&2
  exit 1
fi

cd "$APP_DIR"

echo "This will DROP and REPLACE collections in the 'wabot' database with the"
echo "contents of: $archive"
read -r -p "Type 'restore' to continue: " confirm
if [[ "$confirm" != "restore" ]]; then
  echo "Aborted."
  exit 1
fi

docker compose -f "$COMPOSE_FILE" exec -T mongo \
  mongorestore --archive --gzip --drop < "$archive"

echo "Restore complete."
