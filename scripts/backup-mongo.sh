#!/usr/bin/env bash
set -euo pipefail

# Dumps the production Mongo database to a compressed archive, rotates local
# backups, and (if configured) copies the archive off the VPS with rclone.
#
# Run on the VPS, e.g. via cron:
#   17 3 * * * cd ~/whatsapp-bot && ./backup-mongo.sh >> backup.log 2>&1
#
# Config (env or edit below):
#   APP_DIR              default: script's own directory
#   COMPOSE_FILE         default: docker-compose.prod.yml
#   BACKUP_DIR           default: $APP_DIR/backups
#   RETENTION_DAYS       default: 14 (local copies older than this are deleted)
#   RCLONE_REMOTE        optional, e.g. "s3:my-bucket/whatsapp-bot-backups"
#                         if set, the archive is also copied off-box with `rclone copy`.

APP_DIR="${APP_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
BACKUP_DIR="${BACKUP_DIR:-$APP_DIR/backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
RCLONE_REMOTE="${RCLONE_REMOTE:-}"

cd "$APP_DIR"
mkdir -p "$BACKUP_DIR"

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
archive="$BACKUP_DIR/mongo-${timestamp}.archive.gz"
tmp_archive="${archive}.tmp"

echo "[backup-mongo] Dumping to ${archive}..."
docker compose -f "$COMPOSE_FILE" exec -T mongo \
  mongodump --archive --gzip --db=wabot > "$tmp_archive"

if [[ ! -s "$tmp_archive" ]]; then
  echo "[backup-mongo] ERROR: dump produced an empty file, aborting" >&2
  rm -f "$tmp_archive"
  exit 1
fi

mv "$tmp_archive" "$archive"
echo "[backup-mongo] OK: $(du -h "$archive" | cut -f1) written"

if [[ -n "$RCLONE_REMOTE" ]]; then
  if command -v rclone >/dev/null 2>&1; then
    echo "[backup-mongo] Copying to $RCLONE_REMOTE..."
    rclone copy "$archive" "$RCLONE_REMOTE"
  else
    echo "[backup-mongo] WARNING: RCLONE_REMOTE is set but rclone is not installed; skipping off-box copy" >&2
  fi
fi

echo "[backup-mongo] Pruning local backups older than ${RETENTION_DAYS} days..."
find "$BACKUP_DIR" -name 'mongo-*.archive.gz' -mtime "+${RETENTION_DAYS}" -print -delete

echo "[backup-mongo] Done."
