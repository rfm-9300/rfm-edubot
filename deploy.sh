#!/usr/bin/env bash
set -euo pipefail

# Emergency fallback when CI can't publish: builds and pushes a release's image from this checkout.
# Production normally gets its image from the Release workflow (push a tag, scripts/release.sh).

REGISTRY_IMAGE="${REGISTRY_IMAGE:-ghcr.io/rfm-9300/whatsapp-bot}"
TAG="${TAG:-$(git describe --tags --exact-match 2>/dev/null || true)}"
if [[ -z "$TAG" ]]; then
  echo "Set TAG to the release to build (e.g. TAG=v1.4.1), or run this on a commit that has a release tag." >&2
  exit 1
fi
PLATFORM="${PLATFORM:-linux/amd64}"
FULL_IMAGE_NAME="${REGISTRY_IMAGE}:${TAG}"

echo "Building ${FULL_IMAGE_NAME} for ${PLATFORM}..."
docker build --platform "${PLATFORM}" -t "${FULL_IMAGE_NAME}" .

echo "Pushing ${FULL_IMAGE_NAME}..."
docker push "${FULL_IMAGE_NAME}"

echo "Image pushed: ${FULL_IMAGE_NAME}"
echo
echo "Production normally deploys from the Release workflow when a release tag is pushed."
echo "Manual VPS steps (emergency fallback, see DEPLOYMENT_RUNBOOK.md):"
echo "  scp docker-compose.prod.yml scripts/remote-deploy.sh hillsong-vps:~/whatsapp-bot/"
echo "  ssh hillsong-vps 'chmod +x ~/whatsapp-bot/remote-deploy.sh && TAG=${TAG} ~/whatsapp-bot/remote-deploy.sh'"
echo "  then, once it is healthy, copy the host scripts (mongo-databases.sh, backup-mongo.sh, backup-runner.sh,"
echo "  restore-mongo.sh, restore-tenant.sh, healthcheck-alert.sh) from scripts/ to ~/whatsapp-bot/"
