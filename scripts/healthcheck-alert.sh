#!/usr/bin/env bash
set -euo pipefail

# Standalone uptime check, independent of deploys. Run on the VPS via cron
# every few minutes:
#   */5 * * * * cd ~/whatsapp-bot && ./healthcheck-alert.sh >> healthcheck.log 2>&1
#
# Alerts (via the same webhook as remote-deploy.sh) only on state *transitions*
# (up -> down, down -> up), not on every failed check, so it doesn't spam.
#
# Config:
#   HEALTH_URL                default: http://localhost:8080/ready
#   DEPLOY_ALERT_WEBHOOK_URL   required to actually send alerts; without it this just logs.
#   STATE_FILE                default: $APP_DIR/.healthcheck-state (contents: "up" or "down")

APP_DIR="${APP_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
HEALTH_URL="${HEALTH_URL:-http://localhost:8080/ready}"
DEPLOY_ALERT_WEBHOOK_URL="${DEPLOY_ALERT_WEBHOOK_URL:-}"
STATE_FILE="${STATE_FILE:-$APP_DIR/.healthcheck-state}"

notify() {
  local message="$1"
  if [[ -n "$DEPLOY_ALERT_WEBHOOK_URL" ]]; then
    local escaped="${message//\\/\\\\}"
    escaped="${escaped//\"/\\\"}"
    curl -fsS -X POST -H 'Content-Type: application/json' \
      -d "{\"text\": \"${escaped}\"}" \
      "$DEPLOY_ALERT_WEBHOOK_URL" >/dev/null 2>&1 || echo "notify: webhook call failed" >&2
  fi
}

previous_state="up"
if [[ -f "$STATE_FILE" ]]; then
  previous_state="$(cat "$STATE_FILE")"
fi

if curl -fsS --max-time 10 "$HEALTH_URL" >/dev/null 2>&1; then
  current_state="up"
else
  current_state="down"
fi

if [[ "$current_state" != "$previous_state" ]]; then
  if [[ "$current_state" == "down" ]]; then
    echo "$(date -u +%FT%TZ) whatsapp-bot went DOWN (${HEALTH_URL})" >&2
    notify "🔴 whatsapp-bot is DOWN — ${HEALTH_URL} is not responding."
  else
    echo "$(date -u +%FT%TZ) whatsapp-bot recovered" >&2
    notify "🟢 whatsapp-bot recovered — ${HEALTH_URL} is responding again."
  fi
  echo "$current_state" > "$STATE_FILE"
fi
