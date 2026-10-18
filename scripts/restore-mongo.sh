#!/usr/bin/env bash
set -euo pipefail

# Restores whole databases from archives made by backup-mongo.sh. DESTRUCTIVE: uses --drop, which
# replaces each collection in the archive with the archive's copy.
#
# Usage: ./restore-mongo.sh <archive> [<archive> ...]
#   platform-<timestamp>.archive.gz  replaces the platform database (tenants, sign-ins, settings)
#   data-<timestamp>.archive.gz      replaces every company's data; one company: restore-tenant.sh
#   mongo-<timestamp>.archive.gz     an archive from before the database split, holding both: its
#                                    platform collections go to the platform database, its runtime
#                                    state is skipped
# A full restore takes the platform and data archives of the same timestamp.
#
# Run on the VPS with the app stack up (mongo service running).

APP_DIR="${APP_DIR:-$HOME/whatsapp-bot}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
# shellcheck source-path=SCRIPTDIR source=mongo-databases.sh
source "$(cd "$(dirname "$0")" && pwd)/mongo-databases.sh"

if [[ $# -eq 0 ]]; then
  echo "Usage: restore-mongo.sh <archive> [<archive> ...]" >&2
  exit 1
fi

archives=()
plans=()
for arg in "$@"; do
  if [[ ! -f "$arg" ]]; then
    echo "No such file: $arg" >&2
    exit 1
  fi
  # Absolute before the cd below, so a path relative to the caller's directory still resolves.
  archives+=("$(cd "$(dirname "$arg")" && pwd)/$(basename "$arg")")
  case "$(basename "$arg")" in
    platform-*.archive.gz) plans+=("the platform database '$PLATFORM_DB'") ;;
    data-*.archive.gz) plans+=("every company's data in '$DATA_DB'") ;;
    mongo-*.archive.gz) plans+=("the platform database '$PLATFORM_DB' and every company's data in '$DATA_DB' (an archive from before the split)") ;;
    *)
      echo "$(basename "$arg") is not a backup-mongo.sh archive (platform-, data- or mongo-<timestamp>.archive.gz)." >&2
      exit 1
      ;;
  esac
done

cd "$APP_DIR"

echo "This will DROP and REPLACE, with the contents of the archives:"
for i in "${!archives[@]}"; do
  echo "  ${plans[$i]}, from $(basename "${archives[$i]}")"
done
read -r -p "Type 'restore' to continue: " confirm
if [[ "$confirm" != "restore" ]]; then
  echo "Aborted."
  exit 1
fi

for archive in "${archives[@]}"; do
  args=(--archive --gzip --drop)
  case "$(basename "$archive")" in
    platform-*) args+=(--nsInclude "$PLATFORM_DB.*") ;;
    data-*) args+=(--nsInclude "$DATA_DB.*") ;;
    mongo-*)
      args+=(--nsInclude "$DATA_DB.*")
      for name in "${RUNTIME_COLLECTIONS[@]}"; do args+=(--nsExclude "$DATA_DB.$name"); done
      for name in "${PLATFORM_COLLECTIONS[@]}"; do args+=(--nsFrom "$DATA_DB.$name" --nsTo "$PLATFORM_DB.$name"); done
      ;;
  esac
  echo "Restoring $(basename "$archive")..."
  docker compose -f "$COMPOSE_FILE" exec -T mongo mongorestore "${args[@]}" < "$archive"
done

echo "Restore complete. Restart the app so it reloads tenants and settings:"
echo "  cd $APP_DIR && docker compose -f $COMPOSE_FILE restart app"
