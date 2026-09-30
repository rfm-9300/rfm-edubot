#!/usr/bin/env bash
set -euo pipefail

# Runs the backup someone asked for in the backoffice (Backups → Back up now). The app never runs
# anything on the host: it only leaves request.json in the control directory, which this script
# checks. Nothing is printed while there is no request.
#
# Run on the VPS from cron every minute, with the same settings as the nightly backup-mongo.sh:
#   * * * * * cd ~/whatsapp-bot && APP_DIR=... RCLONE_REMOTE=... ./backup-runner.sh >> backup.log 2>&1
#
# Control directory (mounted into the app as /backup-control, see docker-compose.prod.yml):
#   heartbeat          touched on every check, so the backoffice can tell the runner is alive
#   request.json       left by the app; claimed by renaming it to running.json
#   last-request.json  the request of the last finished run
#   last.json          {id, exitCode, startedAt, finishedAt, archive} of the last finished run
#   last.log           that run's output
#
# Config (env):
#   APP_DIR        default: $HOME/whatsapp-bot
#   CONTROL_DIR    default: $APP_DIR/backup-control
#   BACKUP_SCRIPT  default: backup-mongo.sh next to this script
#   Anything backup-mongo.sh reads (RCLONE_REMOTE, RETENTION_DAYS, ...) is passed through.

APP_DIR="${APP_DIR:-$HOME/whatsapp-bot}"
CONTROL_DIR="${CONTROL_DIR:-$APP_DIR/backup-control}"
BACKUP_SCRIPT="${BACKUP_SCRIPT:-$(cd "$(dirname "$0")" && pwd)/backup-mongo.sh}"

mkdir -p "$CONTROL_DIR"
touch "$CONTROL_DIR/heartbeat"
[[ -f "$CONTROL_DIR/request.json" ]] || exit 0

mv "$CONTROL_DIR/request.json" "$CONTROL_DIR/running.json"
touch "$CONTROL_DIR/running.json"
id="$(jq -r '.id // ""' "$CONTROL_DIR/running.json" 2>/dev/null || true)"
started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "[backup-runner] $started backup requested from the backoffice (request ${id:-unknown}), starting"

set +e
"$BACKUP_SCRIPT" > "$CONTROL_DIR/last.log" 2>&1
code=$?
set -e
cat "$CONTROL_DIR/last.log"

archive="$(sed -n 's/^\[backup-mongo\] ARCHIVE //p' "$CONTROL_DIR/last.log" | tail -n 1)"
jq -n --arg id "$id" --argjson exitCode "$code" --arg startedAt "$started" \
  --arg finishedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg archive "$archive" \
  '{id: $id, exitCode: $exitCode, startedAt: $startedAt, finishedAt: $finishedAt, archive: $archive}' \
  > "$CONTROL_DIR/last.json.tmp"
mv "$CONTROL_DIR/last.json.tmp" "$CONTROL_DIR/last.json"
mv "$CONTROL_DIR/running.json" "$CONTROL_DIR/last-request.json"
echo "[backup-runner] finished with exit code $code"
