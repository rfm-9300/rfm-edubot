#!/usr/bin/env bash
set -euo pipefail

# Restores one company's data from a backup-mongo.sh archive and leaves every other company alone.
# DESTRUCTIVE for that company: its documents in each collection of the archive are replaced by the
# archive's (made since are gone, deleted since come back). Its tenant record, sign-ins and token
# usage are platform data and stay as they are; collections that aren't in the archive too.
#
# Usage: ./restore-tenant.sh <archive> <company-slug>
#   archive: data-<timestamp>.archive.gz, or mongo-<timestamp>.archive.gz from before the split
#
# The archive goes into a scratch database first; then, collection by collection, the company's
# documents (tenantId, or _id for the per-company settings) are deleted and copied back from it.
# Suspend the company in the backoffice first if it is busy, and pause its agents if it has them:
# a run queued at backup time is queued again.
#
# Run on the VPS with the app stack up (mongo service running).

APP_DIR="${APP_DIR:-$HOME/whatsapp-bot}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
# shellcheck source-path=SCRIPTDIR source=mongo-databases.sh
source "$(cd "$(dirname "$0")" && pwd)/mongo-databases.sh"

archive="${1:-}"
slug="${2:-}"
if [[ -z "$archive" || -z "$slug" ]]; then
  echo "Usage: restore-tenant.sh <data-archive> <company-slug>" >&2
  exit 1
fi
if [[ ! -f "$archive" ]]; then
  echo "No such file: $archive" >&2
  exit 1
fi
case "$(basename "$archive")" in
  data-*.archive.gz | mongo-*.archive.gz) ;;
  *)
    echo "$(basename "$archive") holds no company data; use a data-<timestamp>.archive.gz." >&2
    exit 1
    ;;
esac
# Absolute before the cd below, so a path relative to the caller's directory still resolves.
archive="$(cd "$(dirname "$archive")" && pwd)/$(basename "$archive")"

cd "$APP_DIR"

# Values reach mongosh as environment variables, so a slug never becomes part of the script.
mongo_exec() {
  docker compose -f "$COMPOSE_FILE" exec -T "$@"
}

company="$(mongo_exec -e SLUG="$slug" -e PLATFORM_DB="$PLATFORM_DB" mongo mongosh --quiet --eval '
  const t = db.getSiblingDB(process.env.PLATFORM_DB).tenants.findOne({ slug: process.env.SLUG }, { name: 1, status: 1 });
  print(t ? [t._id.toHexString(), t.status, t.name].join("\t") : "");
' </dev/null)"
if [[ -z "$company" ]]; then
  echo "No company with the slug '$slug' in $PLATFORM_DB." >&2
  exit 1
fi
IFS=$'\t' read -r tenant_id status name <<<"$company"

echo "This replaces the data of $name ($slug, $status, id $tenant_id) in '$DATA_DB'"
echo "with its data in $(basename "$archive"). Other companies are not touched."
read -r -p "Type the company's slug to continue: " confirm
if [[ "$confirm" != "$slug" ]]; then
  echo "Aborted."
  exit 1
fi

scratch="restore_$(date -u +%Y%m%dT%H%M%SZ)"
skip=("${PLATFORM_COLLECTIONS[@]}" "${RUNTIME_COLLECTIONS[@]}")
args=(--archive --gzip --noIndexRestore --nsInclude "$DATA_DB.*" --nsFrom "$DATA_DB.*" --nsTo "$scratch.*")
for name in "${skip[@]}"; do args+=(--nsExclude "$DATA_DB.$name"); done

echo "Loading $(basename "$archive") into the scratch database $scratch..."
mongo_exec mongo mongorestore --quiet "${args[@]}" < "$archive"

skip_list="$(IFS=,; echo "${skip[*]}")"
# shellcheck disable=SC2016 # the $ operators belong to the JavaScript
mongo_exec -e SCRATCH="$scratch" -e DATA_DB="$DATA_DB" -e TENANT_ID="$tenant_id" -e SKIP="$skip_list" mongo mongosh --quiet --eval '
  const scratch = db.getSiblingDB(process.env.SCRATCH);
  const live = db.getSiblingDB(process.env.DATA_DB);
  const id = ObjectId(process.env.TENANT_ID);
  const skip = new Set(process.env.SKIP.split(","));
  const mine = { $or: [{ tenantId: id }, { _id: id }] };
  try {
    const rows = [];
    for (const name of scratch.getCollectionNames().filter(n => !skip.has(n)).sort()) {
      const before = live.getCollection(name).countDocuments(mine);
      live.getCollection(name).deleteMany(mine);
      scratch.getCollection(name).aggregate([
        { $match: mine },
        { $merge: { into: { db: process.env.DATA_DB, coll: name }, on: "_id", whenMatched: "replace", whenNotMatched: "insert" } },
      ]).toArray();
      const after = live.getCollection(name).countDocuments(mine);
      if (before || after) rows.push(name.padEnd(32) + String(before).padStart(8) + String(after).padStart(8));
    }
    print("collection".padEnd(32) + "before".padStart(8) + "after".padStart(8));
    rows.forEach(r => print(r));
  } finally {
    scratch.dropDatabase();
  }
  print("Dropped the scratch database " + process.env.SCRATCH + ".");
' </dev/null

echo "Restored $slug from $(basename "$archive")."
echo "Reload its pipeline (backoffice: the company's record, Reload pipeline) or restart the app,"
echo "so the bot answers with the restored persona and chat flows."
