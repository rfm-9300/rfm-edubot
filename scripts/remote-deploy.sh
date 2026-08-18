#!/usr/bin/env bash
set -euo pipefail

# Runs on the production VPS. Pulls the published image and recreates the app
# container without deleting Mongo volumes or touching .env.

APP_DIR="${APP_DIR:-$HOME/whatsapp-bot}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
TAG="${TAG:-latest}"
REGISTRY_IMAGE="${REGISTRY_IMAGE:-ghcr.io/rfm-9300/whatsapp-bot}"
HEALTH_RETRIES="${HEALTH_RETRIES:-30}"
HEALTH_SLEEP_SECONDS="${HEALTH_SLEEP_SECONDS:-2}"

cd "$APP_DIR"

if [[ -n "${GHCR_TOKEN:-}" ]]; then
  echo "${GHCR_TOKEN}" | docker login ghcr.io -u "${GHCR_USERNAME:-github}" --password-stdin
fi

export TAG
echo "Deploying ${REGISTRY_IMAGE}:${TAG}"

docker compose -f "${COMPOSE_FILE}" pull
# Recreate the app from the new image. Do not `down` the stack: that would bounce
# Mongo on every merge. Volumes and .env are left untouched.
docker compose -f "${COMPOSE_FILE}" up -d --remove-orphans

logout_ghcr() {
  if [[ -n "${GHCR_TOKEN:-}" ]]; then
    docker logout ghcr.io >/dev/null 2>&1 || true
  fi
}
trap logout_ghcr EXIT

container_ip() {
  local cid
  cid="$(docker compose -f "${COMPOSE_FILE}" ps -q app)"
  docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{println}}{{end}}' "${cid}" \
    | awk 'NF { print; exit }'
}

echo "Waiting for /health and /ready..."
ip=""
for _ in $(seq 1 "${HEALTH_RETRIES}"); do
  ip="$(container_ip || true)"
  if [[ -n "${ip}" ]] \
    && curl -fsS "http://${ip}:8080/health" >/dev/null \
    && curl -fsS "http://${ip}:8080/ready" >/dev/null; then
    echo "Health checks passed at ${ip}:8080"
    docker compose -f "${COMPOSE_FILE}" ps
    exit 0
  fi
  sleep "${HEALTH_SLEEP_SECONDS}"
done

echo "Health checks failed after ${HEALTH_RETRIES} attempts" >&2
docker compose -f "${COMPOSE_FILE}" ps >&2 || true
docker compose -f "${COMPOSE_FILE}" logs --tail=100 app >&2 || true
exit 1
