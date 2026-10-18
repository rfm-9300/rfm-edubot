#!/usr/bin/env bash
set -euo pipefail

# Backs up production Mongo as two compressed archives, rotates local backups, and (if configured)
# copies the archives off the VPS with rclone:
#   platform-<timestamp>.archive.gz  the platform database: tenants, sign-ins, backoffice admins,
#                                    platform settings, token usage
#   data-<timestamp>.archive.gz      the companies' data; restore-tenant.sh restores one company from it
# The runtime database (webhook dedup, job leases, clock-in challenges) is not backed up.
#
# Run on the VPS, e.g. via cron:
#   17 3 * * * cd ~/whatsapp-bot && ./backup-mongo.sh >> backup.log 2>&1
#
# Config (env or edit below):
#   APP_DIR              default: $HOME/whatsapp-bot (the production app directory)
#   COMPOSE_FILE         default: docker-compose.prod.yml
#   BACKUP_DIR           default: $APP_DIR/backups
#   RETENTION_DAYS       default: 14 (local copies older than this are deleted)
#   RCLONE_REMOTE        optional, e.g. "s3:my-bucket/whatsapp-bot-backups"
#                         if set, the archives are also copied off-box with `rclone copy`.
#   DATA_DB, PLATFORM_DB see mongo-databases.sh

APP_DIR="${APP_DIR:-$HOME/whatsapp-bot}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
BACKUP_DIR="${BACKUP_DIR:-$APP_DIR/backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
RCLONE_REMOTE="${RCLONE_REMOTE:-}"
# shellcheck source-path=SCRIPTDIR source=mongo-databases.sh
source "$(cd "$(dirname "$0")" && pwd)/mongo-databases.sh"

cd "$APP_DIR"
mkdir -p "$BACKUP_DIR"

# One backup at a time: the nightly cron and a backoffice request (backup-runner.sh) can start together.
if command -v flock >/dev/null 2>&1; then
  exec 9>"$APP_DIR/.backup.lock"
  if ! flock -w 1800 9; then
    echo "[backup-mongo] ERROR: another backup held the lock for 30 minutes, giving up" >&2
    exit 1
  fi
fi

mongo_exec() {
  docker compose -f "$COMPOSE_FILE" exec -T mongo "$@"
}

tenants="$(mongo_exec mongosh --quiet --eval "db.getSiblingDB('$PLATFORM_DB').tenants.estimatedDocumentCount()" </dev/null | tr -d '[:space:]')"
if [[ ! "$tenants" =~ ^[1-9][0-9]*$ ]]; then
  echo "[backup-mongo] ERROR: $PLATFORM_DB has no tenants, so the running app keeps its data elsewhere." >&2
  echo "[backup-mongo] An app from before the database split needs that release's backup-mongo.sh." >&2
  exit 1
fi

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
platform_archive="$BACKUP_DIR/platform-${timestamp}.archive.gz"
data_archive="$BACKUP_DIR/data-${timestamp}.archive.gz"
trap 'rm -f "$platform_archive.tmp" "$data_archive.tmp"' EXIT

excluded=()
for name in "${PLATFORM_COLLECTIONS[@]}" "${RUNTIME_COLLECTIONS[@]}"; do
  excluded+=("--excludeCollection=$name")
done

echo "[backup-mongo] Dumping $PLATFORM_DB to $(basename "$platform_archive")..."
mongo_exec mongodump --quiet --archive --gzip --db="$PLATFORM_DB" </dev/null > "$platform_archive.tmp"
# The moved collections' originals stay in the data database for a rollback; they aren't company data.
echo "[backup-mongo] Dumping $DATA_DB to $(basename "$data_archive")..."
mongo_exec mongodump --quiet --archive --gzip --db="$DATA_DB" "${excluded[@]}" </dev/null > "$data_archive.tmp"

for archive in "$platform_archive" "$data_archive"; do
  if [[ ! -s "$archive.tmp" ]]; then
    echo "[backup-mongo] ERROR: the dump for $(basename "$archive") is empty, aborting" >&2
    exit 1
  fi
done

for archive in "$platform_archive" "$data_archive"; do
  mv "$archive.tmp" "$archive"
  echo "[backup-mongo] OK: $(basename "$archive"), $(du -h "$archive" | cut -f1)"
  # backup-runner.sh reads these lines to report the archives in the backoffice.
  echo "[backup-mongo] ARCHIVE $(basename "$archive")"
done

if [[ -n "$RCLONE_REMOTE" ]]; then
  if command -v rclone >/dev/null 2>&1; then
    for archive in "$platform_archive" "$data_archive"; do
      echo "[backup-mongo] Copying $(basename "$archive") to $RCLONE_REMOTE..."
      rclone copy "$archive" "$RCLONE_REMOTE"
      # The backoffice shows an archive as copied off the VPS when this marker exists.
      touch "${archive}.uploaded"
    done
  else
    echo "[backup-mongo] WARNING: RCLONE_REMOTE is set but rclone is not installed; skipping off-box copy" >&2
  fi
fi

echo "[backup-mongo] Pruning local backups older than ${RETENTION_DAYS} days..."
for kind in platform data mongo; do
  find "$BACKUP_DIR" -name "${kind}-*.archive.gz" -mtime "+${RETENTION_DAYS}" -print -delete
  find "$BACKUP_DIR" -name "${kind}-*.archive.gz.uploaded" -mtime "+${RETENTION_DAYS}" -delete
done

echo "[backup-mongo] Done."
