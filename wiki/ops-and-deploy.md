---
updated: 2026-10-20
---

# Production ops and deploy lore

What production looks like and the traps found running it. How to deploy is in [`DEPLOYMENT_RUNBOOK.md`](../DEPLOYMENT_RUNBOOK.md); this page holds the reasons and the gotchas. Which Mongo database holds what: [mongo-databases.md](mongo-databases.md).

## Releases by tag (PR #85)

Until PR #85 every merge to `main` deployed, so production ran whatever had been merged last; Rodrigo
wanted control of releases through git tags. Now merging runs CI only (`ci.yml`), and production
changes only when a tag `vMAJOR.MINOR.PATCH` is pushed (`scripts/release.sh`). Decisions:

- **The tag must point at a commit on `main`** (`release.yml` checks it), so only merged work ships,
  and a malformed tag fails loudly instead of being ignored by a trigger filter.
- **The release builds its own image,** `:<tag>` plus `:<sha>`, after the tests; there is no image per
  merge to promote. Release notes come from GitHub's generated notes (merged PRs since the last tag).
- **Rollback is a manual run of Deploy WhatsApp Bot with an older tag:** it reuses that release's image
  and takes the compose file and host scripts from that tag. `deploy.yml` serves both, called by
  `release.yml` (`workflow_call`) or by hand (`workflow_dispatch`).
- **Host scripts install only after a healthy deploy,** so a release that fails its health checks and
  rolls back leaves the scripts matching the release still running (it matters since the database
  split changed what a backup dumps).
- **Releases pull only the app image.** On 2026-10-14 a deploy also pulled a newer `mongo:7` and
  recreated Mongo. Updating Mongo is now its own runbook step.

## Hosting

Production runs on `hillsong-vps` (the name is historical). The same box runs the `websites-thebots` stack, whose Caddy serves the marketing site and proxies selected paths of the dashboard's domain to this app; that stack belongs to another project and nothing here touches it. Its live Caddyfile on the VPS is the source of truth: this repo's `deploy/Caddyfile` is stale (no routes beyond `/admin` and `/webhook`), and the runbook's Caddy block lists what the live one must route to the app. Two consequences: the public `/health` answers from the marketing site (below), and a path the Caddy doesn't proxy never reaches the app (`/integrations*` didn't until 2026-10-09, see [agents-automations.md](agents-automations.md)).

## Ops gotcha: public `/health` is not the bot

`https://thebotslab.pt/health` and `/ready` (the same on `.eu`) return the marketing site's page:
the shared `websites-thebots` Caddy does not route them to the bot, although the runbook's Caddy
snippet lists them. The status was 200 when checked on 2026-09-28 and is 404 since at least
2026-10-09; either way it says nothing about the bot. Check health the way the runbook and
`remote-deploy.sh` do, against the app container's IP over `ssh hillsong-vps`.

Log noise after a deploy (seen 2026-09-29): dozens of `WARN` lines are normal and not a regression
signal. They are PDFBox falling back from Helvetica to LiberationSans (one pair per generated PDF),
PDFBox rebuilding its font cache once per new container, and the startup `Missing config keys`
line. It names `app.admin.adminPasswordHash` (password login is off in prod) and, while Gmail isn't
configured in `.env` ([agents-automations.md](agents-automations.md)), the three
`app.google.oauth.*` keys and `app.integrations.encryptionKey` (seen 2026-10-11). Count
`"level":"ERROR"` instead.

## Ops gotcha: there is no `:latest` (fixed in PR #85)

Until PR #85 CI pushed `:<sha>` and `:latest` to GHCR while the VPS deployed by SHA, so its local
`:latest` only moved when someone deployed `latest` by hand; on 2026-09-28 it pointed at a month-old
image. The prod compose file fell back to `:latest` when `TAG` was unset, so a hand-run
`docker compose up -d` would silently downgrade the app, and GHCR's `:latest` was the newest `main`
build even when its deploy had failed and rolled back. Now releases push no `:latest`, and the
compose file's fallback is the placeholder `set-TAG-to-a-release`: `ps`, `logs`, `exec` and `restart`
work without `TAG`, while a command that would recreate the app fails to pull and leaves the running
container alone (checked with Compose v5). Hand-run Compose still pins `TAG=$(cat .last-good-tag)`.
GHCR keeps the old `:latest` (the last `main` build before PR #85); nothing reads it.

Each deploy leaves about 354 MB of
image; roughly 40 had filled the 25 GB disk to 83% before a manual cleanup on 2026-09-28 brought it
to 35%. Since `29e2dc9` (same day), `remote-deploy.sh` removes older app images after every healthy
deploy, keeping the new one and the rollback target. It is best-effort and never fails a deploy.
A commit that changes no app code builds the same image under a second SHA tag, so the cleanup can
report "Removed 0" while an older tag stays: it is the same image and takes no extra space.

## Mongo backups

**Two archives per backup (PR #85).** Since the database split each backup is a
`platform-<stamp>.archive.gz` and a `data-<stamp>.archive.gz`, the runtime database isn't backed up,
and one company can be restored from the data archive with `restore-tenant.sh`
([mongo-databases.md](mongo-databases.md)). The older `mongo-<stamp>` archives below hold everything;
both restore scripts still take them. Everything below about cron, locking, the runner and GCS holds
for both.

**Production Mongo backups (since 2026-09-28).** Until that day there were none: the runbook's one-time cron setup had never been done. A nightly cron job on the host now runs `backup-mongo.sh`, writing `~/whatsapp-bot/backups/mongo-*.archive.gz` with 14-day retention. The first archive restored cleanly into a throwaway container with every collection's count matching prod. Since the evening of 2026-09-28 each archive is also copied off-box to Google Cloud Storage (below); uptime alerting is still missing.

**VPS scripts and `APP_DIR` (fixed 2026-09-28, commit `f70c7d0`):** `backup-mongo.sh`,
`restore-mongo.sh` and `healthcheck-alert.sh` used to default `APP_DIR` to their parent directory,
which resolved to `/root` on the VPS (deploys copy them flat into `~/whatsapp-bot/`), so the
runbook's backup and restore commands failed as written. They now default to `$HOME/whatsapp-bot`
like `remote-deploy.sh`, and `restore-mongo.sh` resolves the archive path before its `cd`. Verified
on the VPS: `cd ~/whatsapp-bot && ./backup-mongo.sh` works with no overrides, and an emergency
restore is `cd ~/whatsapp-bot && ./restore-mongo.sh backups/<archive>` (typed confirmation, uses
`--drop`). The cron line still passes `APP_DIR` explicitly, which is harmless. The uptime script's
default `HEALTH_URL` (`localhost:8080` on the host) remains wrong because prod does not publish
8080; it must probe the container IP like `remote-deploy.sh` does before it can be installed.

- **Manual backups from the backoffice** (shipped 2026-09-30, `8d3af97`). The app only writes
  `request.json` into the mounted `~/whatsapp-bot/backup-control`; the host's `backup-runner.sh`
  (a host cron job every minute, installed the same day next to the nightly one) runs
  `backup-mongo.sh`, which now takes a `flock` so it can't overlap the nightly job. `<archive>.uploaded` markers show off-box copies;
  archives from before that day show "Not recorded" although the nightly job uploaded them.
  Why this shape: the internet-facing app must never hold Docker access or the GCS key.

- **The PDF volume is in no backup.** The archives are Mongo only; files under `PDF_STORAGE_PATH`
  (the `pdf_data` volume) are not: the companies' logos and, since PR #88, the Building photos
  ([building-projects.md](building-projects.md)). Restoring a company brings back its photo records
  without their files. Copying that volume off-box with the archives is still to do.

- **Off-box Mongo backups** go to a private Cloud Storage bucket in the Firebase/GCP project `thebotslab` (90-day lifecycle, 7-day soft delete). The VPS uploads with a service account that can only create and read objects in that bucket: it gets 403 on delete, so a compromised VPS can't wipe the copies. The runbook has the restore commands. Browse the archives in the Cloud console (Storage); the Firebase console's Storage tab doesn't list the bucket. Keep it that way. Any Google account can get a Firebase ID token in this project, because `ADMIN_EMAILS` is enforced only by our server, so importing the bucket into Firebase Storage with a rule like `request.auth != null` would expose the backups.

## Platform settings override `.env`

`RuntimeConfig` is the config loaded from the environment plus the backoffice's platform settings (Mongo `platform_settings`), and a non-blank override wins over the `.env` value, secrets included (`WA_APP_SECRET`, `ADMIN_PASSWORD_HASH` and the other `PlatformSettingKey`s). So the VPS `.env` isn't necessarily what prod runs with: check the key's source in the backoffice's platform settings (`env` or `override`) before rotating, changing or clearing either, because clearing an override falls back to whatever `.env` holds.

## Scripts fed through `ssh … 'bash -s'`

A command that reads stdin swallows the rest of a script fed to `ssh hillsong-vps 'bash -s'` through a heredoc. `backup-mongo.sh` does, and so does `docker compose exec -T`. Run such a command last or give it `</dev/null`.
