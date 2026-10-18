#!/usr/bin/env bash
set -euo pipefail

# Runs on the production VPS. Pulls the release's image (TAG, e.g. v1.4.0) and
# recreates the app container without deleting Mongo volumes or touching .env.
# Only the app image is pulled: Mongo's image changes only when someone updates
# it on purpose (DEPLOYMENT_RUNBOOK.md). After a healthy deploy it removes older
# images of this repository, keeping the new one and the rollback target.
#
# On a failed health check, automatically rolls back to the last tag that
# passed health checks, so a bad release doesn't leave prod down. The script
# still exits non-zero in that case, so CI reports the deploy as failed.
#
# Optional: set DEPLOY_ALERT_WEBHOOK_URL to a Slack/Discord-compatible
# incoming webhook URL to get a message on deploy failure/rollback.

APP_DIR="${APP_DIR:-$HOME/whatsapp-bot}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
TAG="${TAG:?Set TAG to the release to deploy, e.g. TAG=v1.4.0}"
REGISTRY_IMAGE="${REGISTRY_IMAGE:-ghcr.io/rfm-9300/whatsapp-bot}"
HEALTH_RETRIES="${HEALTH_RETRIES:-30}"
HEALTH_SLEEP_SECONDS="${HEALTH_SLEEP_SECONDS:-2}"
LAST_GOOD_TAG_FILE="${LAST_GOOD_TAG_FILE:-$APP_DIR/.last-good-tag}"
DEPLOY_ALERT_WEBHOOK_URL="${DEPLOY_ALERT_WEBHOOK_URL:-}"

cd "$APP_DIR"

if [[ -n "${GHCR_TOKEN:-}" ]]; then
  echo "${GHCR_TOKEN}" | docker login ghcr.io -u "${GHCR_USERNAME:-github}" --password-stdin
fi

logout_ghcr() {
  if [[ -n "${GHCR_TOKEN:-}" ]]; then
    docker logout ghcr.io >/dev/null 2>&1 || true
  fi
}
trap logout_ghcr EXIT

notify() {
  local message="$1"
  if [[ -n "$DEPLOY_ALERT_WEBHOOK_URL" ]]; then
    # Minimal JSON string escaping (backslash and double-quote); messages here are plain ASCII.
    local escaped="${message//\\/\\\\}"
    escaped="${escaped//\"/\\\"}"
    curl -fsS -X POST -H 'Content-Type: application/json' \
      -d "{\"text\": \"${escaped}\"}" \
      "$DEPLOY_ALERT_WEBHOOK_URL" >/dev/null 2>&1 || echo "notify: webhook call failed" >&2
  fi
}

container_ip() {
  local cid
  cid="$(docker compose -f "${COMPOSE_FILE}" ps -q app)"
  docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{println}}{{end}}' "${cid}" \
    | awk 'NF { print; exit }'
}

wait_for_health() {
  local ip=""
  for _ in $(seq 1 "${HEALTH_RETRIES}"); do
    ip="$(container_ip || true)"
    if [[ -n "${ip}" ]] \
      && curl -fsS "http://${ip}:8080/health" >/dev/null \
      && curl -fsS "http://${ip}:8080/ready" >/dev/null; then
      echo "Health checks passed at ${ip}:8080"
      return 0
    fi
    sleep "${HEALTH_SLEEP_SECONDS}"
  done
  return 1
}

# Removes every image of REGISTRY_IMAGE except the given tags. Best-effort: the
# deploy has already passed health checks, so a failure here must not fail it.
prune_old_images() {
  local keep_ids="" tag ref id removed=0
  for tag in "$@"; do
    keep_ids+=" $(docker image inspect -f '{{.Id}}' "${REGISTRY_IMAGE}:${tag}" 2>/dev/null || true)"
  done
  while read -r ref id; do
    [[ " ${keep_ids} " == *" ${id} "* ]] && continue
    [[ "$ref" == *":<none>" ]] && ref="$id"
    if docker rmi "$ref" >/dev/null 2>&1; then
      removed=$((removed + 1))
    else
      echo "Could not remove old image ${ref}" >&2
    fi
  done < <(docker images --no-trunc --format '{{.Repository}}:{{.Tag}} {{.ID}}' "$REGISTRY_IMAGE")
  echo "Removed ${removed} old image tag(s) of ${REGISTRY_IMAGE}; kept: $*"
}

# Previous known-good tag, so we can roll back to it if this deploy fails.
previous_tag=""
if [[ -f "$LAST_GOOD_TAG_FILE" ]]; then
  previous_tag="$(cat "$LAST_GOOD_TAG_FILE")"
fi

export TAG
echo "Deploying ${REGISTRY_IMAGE}:${TAG} (previous known-good: ${previous_tag:-none recorded})"

docker compose -f "${COMPOSE_FILE}" pull app
# Recreate the app from the new image. Do not `down` the stack: that would bounce
# Mongo on every release. Volumes and .env are left untouched.
docker compose -f "${COMPOSE_FILE}" up -d --remove-orphans

echo "Waiting for /health and /ready..."
if wait_for_health; then
  docker compose -f "${COMPOSE_FILE}" ps
  echo "$TAG" > "$LAST_GOOD_TAG_FILE"
  prune_old_images "$TAG" ${previous_tag:+"$previous_tag"} || true
  exit 0
fi

echo "Health checks failed after ${HEALTH_RETRIES} attempts for tag ${TAG}" >&2
docker compose -f "${COMPOSE_FILE}" ps >&2 || true
docker compose -f "${COMPOSE_FILE}" logs --tail=100 app >&2 || true

if [[ -z "$previous_tag" || "$TAG" == "$previous_tag" ]]; then
  echo "No other known-good tag (${previous_tag:-none recorded}); nothing to roll back to." >&2
  notify "🔴 whatsapp-bot deploy of ${TAG} failed health checks. No rollback available (no other last-good tag). Manual intervention needed."
  exit 1
fi

echo "Rolling back to previous known-good tag: ${previous_tag}" >&2
notify "🟠 whatsapp-bot deploy of ${TAG} failed health checks. Rolling back to ${previous_tag}."

TAG="$previous_tag"
export TAG
docker compose -f "${COMPOSE_FILE}" pull app
docker compose -f "${COMPOSE_FILE}" up -d --remove-orphans

if wait_for_health; then
  docker compose -f "${COMPOSE_FILE}" ps
  echo "Rollback to ${previous_tag} succeeded." >&2
  notify "🟡 Rollback to ${previous_tag} succeeded. ${TAG} needs investigation before redeploying."
else
  echo "Rollback to ${previous_tag} ALSO failed health checks — manual intervention required." >&2
  docker compose -f "${COMPOSE_FILE}" ps >&2 || true
  docker compose -f "${COMPOSE_FILE}" logs --tail=100 app >&2 || true
  notify "🔴🔴 whatsapp-bot: rollback to ${previous_tag} ALSO failed. App may be down. Manual intervention required now."
fi

# Always exit non-zero here: the requested deploy failed, even if we
# successfully protected prod by rolling back to the previous tag.
exit 1
