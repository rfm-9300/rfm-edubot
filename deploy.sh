#!/usr/bin/env bash
set -euo pipefail

REGISTRY_IMAGE="${REGISTRY_IMAGE:-ghcr.io/rfm-9300/whatsapp-bot}"
TAG="${TAG:-latest}"
PLATFORM="${PLATFORM:-linux/amd64}"
FULL_IMAGE_NAME="${REGISTRY_IMAGE}:${TAG}"

echo "Building ${FULL_IMAGE_NAME} for ${PLATFORM}..."
docker build --platform "${PLATFORM}" -t "${FULL_IMAGE_NAME}" .

echo "Pushing ${FULL_IMAGE_NAME}..."
docker push "${FULL_IMAGE_NAME}"

echo "Image pushed: ${FULL_IMAGE_NAME}"
echo
echo "Next deploy steps:"
echo "  ssh hillsong-vps 'mkdir -p ~/whatsapp-bot'"
echo "  scp docker-compose.prod.yml hillsong-vps:~/whatsapp-bot/"
echo "  ssh hillsong-vps 'cd ~/whatsapp-bot && docker compose -f docker-compose.prod.yml pull && docker compose -f docker-compose.prod.yml down && docker compose -f docker-compose.prod.yml up -d'"
