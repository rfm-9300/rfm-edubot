# WhatsApp Bot Deployment Runbook

Production changes only when a release tag is pushed. A tag `vMAJOR.MINOR.PATCH`
on a commit from `main` triggers GitHub Actions: test → build/push the release's
Docker image → SSH to the VPS and recreate the app container. Merging to `main`
runs the tests and deploys nothing.

Manual `./deploy.sh` + SSH remains an emergency fallback. The phrases
"make deploy" / "deploy to VPS" mean "release what is on `main` to production":
cut the next tag (below), not a laptop SSH session.

## What this deploy covers

| Surface | Runtime | Deployed by this pipeline? |
|---------|---------|----------------------------|
| Ktor API, WhatsApp/Instagram webhooks | Docker image `ghcr.io/rfm-9300/whatsapp-bot` | Yes |
| Shared dashboard assets `/admin/{asset}` | Static files inside the same image (`style.css`, catalogs). `/admin` redirects to `/backoffice/` | Yes (same container) |
| Tenant dashboard `/app` | Static files inside the same image | Yes (same container) |
| Operator backoffice `/backoffice` | Static files inside the same image | Yes (same container) |
| Website chat widget `/widget`, `/chat/ws` | Same image | Yes (same container) |
| Legal pages `/privacy`, `/data-deletion` | Same image | Yes (same container) |
| MongoDB | Compose service, persistent `mongo_data` | Left running: releases pull only the app image ([Updating MongoDB](#updating-mongodb)) |
| Marketing site `websites-thebots` | Separate VPS compose + Caddy | **No** — different project |
| Mobile Android/iOS | `mobile/` KMP app | **No** — `mobile-ci.yml` tests only; no store deploy |

`/app` and `/backoffice` are not separate services. They are HTML/JS
resources baked into the fat JAR, and they share CSS/i18n served under `/admin/{asset}`.
One image publish updates both surfaces.

## Production Target

- SSH alias (manual fallback): `hillsong-vps`
- Production directory: `~/whatsapp-bot`
- Compose file: `docker-compose.prod.yml`
- Image: `ghcr.io/rfm-9300/whatsapp-bot:${TAG}`, where `TAG` is a release tag such as `v1.4.0` (there is no `:latest`)
- Deployed release: `~/whatsapp-bot/.last-good-tag`
- Health endpoints: `/health`, `/ready`
- Public hostname: `thebotslab.pt` (Caddy in `websites-thebots`; `thebotslab.eu` serves the same routes)

## Releasing (push a tag)

Workflows: [`ci.yml`](.github/workflows/ci.yml), [`release.yml`](.github/workflows/release.yml),
[`deploy.yml`](.github/workflows/deploy.yml)

```text
PR / merge to main (excluding mobile-only and docs-only changes)
  → CI: test (./gradlew test). Nothing is deployed.

tag vX.Y.Z pushed on a commit that is on main
  → Release: check the tag (format, on main)
  → test (./gradlew test)
  → docker build linux/amd64, push :vX.Y.Z and :<sha> to GHCR
  → Deploy WhatsApp Bot: scp compose + remote-deploy.sh, SSH pull app + up -d,
    curl /health and /ready on the app container (rolls back if they fail),
    then install the release's host scripts (backups, restores, health alert)
  → GitHub release vX.Y.Z with the pull requests merged since the last tag
```

To release what is on `main`, wait for its CI run to pass, then from a checkout of this repo:

```bash
scripts/release.sh patch    # fixes only: v1.4.0 → v1.4.1
scripts/release.sh minor    # new features: v1.4.1 → v1.5.0
scripts/release.sh major    # operators or users must act, or a one-way data migration
```

It tags the current `origin/main` (not your local branch), shows the merges since the last tag, asks
for confirmation and pushes the tag. Then follow the **Release** run in GitHub Actions. By hand it is
`git tag -a v1.5.0 -m "Release v1.5.0" origin/main && git push origin v1.5.0`. A tag that isn't
`vMAJOR.MINOR.PATCH`, or that points at a commit not on `main`, fails the run before anything is built.
Never move or reuse a release tag: publish a new one.

### Rolling back to an earlier release

GitHub → Actions → **Deploy WhatsApp Bot** → Run workflow, with the release tag to go back to
(e.g. `v1.4.1`). It redeploys that release's published image with that release's compose file and
host scripts; nothing is rebuilt. Rolling back past a release that changed how data is stored needs
its notes first; for the database split see [Databases](#databases).

### Automatic rollback

`scripts/remote-deploy.sh` records the tag of every deploy that passed health
checks in `~/whatsapp-bot/.last-good-tag`. If a new deploy's health checks
fail, the script automatically redeploys that previous tag and re-checks
health, instead of leaving the broken container running. The CI job still
fails either way (a failed deploy is a failed deploy), but prod is left on
the last tag known to be healthy rather than on the broken one.

If the rollback itself also fails health checks, the script exits loudly and
prod may be down — that's the one case that needs a human immediately (see
Uptime monitoring below).

The host scripts are installed only after a healthy deploy, so after an automatic
rollback the VPS keeps the scripts of the release that is still running.

Mobile-only changes on `main` do not trigger CI (`paths-ignore: mobile/**`).

### One-time GitHub secrets

Add these as repository secrets, or as secrets on the `production` environment:

| Secret | Purpose |
|--------|---------|
| `DEPLOY_HOST` | VPS hostname or IP (the host behind `ssh hillsong-vps`) |
| `DEPLOY_USER` | SSH user that can run Docker in `~/whatsapp-bot` |
| `DEPLOY_SSH_KEY` | Private key whose public key is in that user's `authorized_keys` |
| `DEPLOY_SSH_KNOWN_HOSTS` | Optional. Output of `ssh-keyscan -H <DEPLOY_HOST>`. If unset, CI uses `ssh-keyscan` at deploy time. |
| `DEPLOY_ALERT_WEBHOOK_URL` | Optional. A Slack or Discord incoming-webhook URL. If set, `remote-deploy.sh` posts a message on deploy failure/rollback. |

Do **not** put production `.env` values in GitHub. Secrets stay on the VPS.

The workflow authenticates to GHCR with `GITHUB_TOKEN` (no extra registry secret).
It forwards that short-lived token to the VPS only for `docker pull`, then logs out.

Until `DEPLOY_HOST` / `DEPLOY_USER` / `DEPLOY_SSH_KEY` are set, the **Test** and
**Build and push image** jobs can succeed and the **Deploy to VPS** job fails
with a missing-secret error.

The Release workflow's **GitHub release** job writes the release notes with
`GITHUB_TOKEN` (`contents: write`, granted to that job only).

### One-time VPS checks

```bash
ssh hillsong-vps "mkdir -p ~/whatsapp-bot && docker network create web_proxy || true"
ssh hillsong-vps "test -f ~/whatsapp-bot/.env && echo '.env present'"
ssh hillsong-vps "curl -fsS --version >/dev/null && echo curl ok"
```

The public GitHub Actions runner must be able to SSH to `DEPLOY_HOST` (firewall
/ security group). Restrict the key to deploy commands if possible.

## Manual fallback

Use only when CI is down or a hotfix must ship from a laptop. It still ships a release tag: create
and push the tag first (`scripts/release.sh`), so the deployed version is on record, then check out
that tag.

### Preflight

```bash
git status --short
docker info
ssh hillsong-vps "echo ok"
```

Do not require a clean worktree, but do not overwrite unrelated local changes.

### Step 1 - Build and Push Image

On a checkout of the release tag (`git checkout v1.4.1`):

```bash
./deploy.sh                 # uses the tag of the checked-out commit
TAG=v1.4.1 ./deploy.sh      # or name it
```

### Step 2 - Sync Deploy Files

```bash
ssh hillsong-vps "mkdir -p ~/whatsapp-bot"
scp docker-compose.prod.yml scripts/remote-deploy.sh hillsong-vps:~/whatsapp-bot/
```

Do not copy `.env` unless Rodrigo explicitly asks. Production secrets should already live on the VPS.

### Step 3 - Deploy on VPS

```bash
ssh hillsong-vps "chmod +x ~/whatsapp-bot/remote-deploy.sh && TAG=v1.4.1 ~/whatsapp-bot/remote-deploy.sh"
```

Once it reports healthy, install the host scripts the way CI does:

```bash
scp scripts/mongo-databases.sh scripts/backup-mongo.sh scripts/backup-runner.sh scripts/restore-mongo.sh scripts/restore-tenant.sh scripts/healthcheck-alert.sh hillsong-vps:~/whatsapp-bot/
ssh hillsong-vps "cd ~/whatsapp-bot && chmod +x backup-mongo.sh backup-runner.sh restore-mongo.sh restore-tenant.sh healthcheck-alert.sh"
```

`scripts/remote-deploy.sh` pulls the app image and runs `docker compose up -d --remove-orphans`.
It does **not** `compose down` the whole stack, so Mongo is not bounced on every deploy, and it
doesn't pull Mongo's image. After health checks pass it deletes older `whatsapp-bot` images,
keeping the new one and the previous known-good one (the rollback target), so deploys don't fill
the disk.

When running Compose by hand, pin `TAG` to `.last-good-tag`. Without it, `ps`, `logs`, `exec` and
`restart` still work, but a command that would recreate the app (`up`, `pull`) fails to pull the
placeholder image `set-TAG-to-a-release` and leaves the running app alone. There is no `:latest` to
fall back to.

Emergency full recreate (still does not delete volumes):

```bash
ssh hillsong-vps 'cd ~/whatsapp-bot && export TAG=$(cat .last-good-tag) && docker compose -f docker-compose.prod.yml pull app && docker compose -f docker-compose.prod.yml down && docker compose -f docker-compose.prod.yml up -d'
```

### Updating MongoDB

Releases never pull `mongo:7`, so Mongo changes only when someone updates it on purpose. Before
this, every deploy pulled it, and a deploy right after the upstream image moved upgraded and
restarted Mongo too (seen 2026-10-14). To take the newest `mongo:7`, back up first, then:

```bash
ssh hillsong-vps 'cd ~/whatsapp-bot && ./backup-mongo.sh && export TAG=$(cat .last-good-tag) && docker compose -f docker-compose.prod.yml pull mongo && docker compose -f docker-compose.prod.yml up -d mongo'
```

Check `/ready` afterwards (Step 4); if the app doesn't reconnect, `docker compose -f docker-compose.prod.yml restart app`.

### Step 4 - Verify

```bash
ssh hillsong-vps "cd ~/whatsapp-bot && docker compose -f docker-compose.prod.yml ps"
ssh hillsong-vps "cd ~/whatsapp-bot && docker compose -f docker-compose.prod.yml logs --tail=100 app"
```

Port `8080` is not published on the host. Probe the container IP (the remote
script already does this) or use the command below. Do not use
`https://thebotslab.pt/health` for this: the shared Caddy does not route `/health`
or `/ready` to the bot, so it answers with the marketing site's page and a `404`.

```bash
ssh hillsong-vps 'cid=$(cd ~/whatsapp-bot && docker compose -f docker-compose.prod.yml ps -q app); ip=$(docker inspect -f "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{println}}{{end}}" "$cid" | awk "NF{print;exit}"); curl -fsS "http://$ip:8080/health" && echo && curl -fsS "http://$ip:8080/ready"'
```

Report success only after containers are up and health checks pass.

## Public routing (websites-thebots, one-time)

The VPS already runs the `websites-thebots-web` Caddy container on ports `80/443`.
The bot production compose intentionally does not publish app port `8080`; public
traffic reaches it through a private Docker network shared with the website Caddy
container.

This repo's CI/CD does **not** reload or redeploy that Caddy stack.

Create the shared proxy network once:

```bash
ssh hillsong-vps "docker network create web_proxy || true"
```

Add path routes to `/root/websites-thebots/Caddyfile` so `/app`, `/backoffice`,
and shared `/admin` assets/APIs are reachable:

```caddy
thebotslab.pt, thebotslab.eu {
  handle /admin* {
    reverse_proxy whatsapp-bot-app-1:8080
  }

  handle /app* {
    reverse_proxy whatsapp-bot-app-1:8080
  }

  handle /backoffice* {
    reverse_proxy whatsapp-bot-app-1:8080
  }

  handle /webhook* {
    reverse_proxy whatsapp-bot-app-1:8080
  }

  handle /widget* {
    reverse_proxy whatsapp-bot-app-1:8080
  }

  handle /chat/ws {
    reverse_proxy whatsapp-bot-app-1:8080
  }

  handle /privacy {
    reverse_proxy whatsapp-bot-app-1:8080
  }

  handle /data-deletion {
    reverse_proxy whatsapp-bot-app-1:8080
  }

  handle /integrations* {
    reverse_proxy whatsapp-bot-app-1:8080
  }

  handle /health {
    reverse_proxy whatsapp-bot-app-1:8080
  }

  handle /ready {
    reverse_proxy whatsapp-bot-app-1:8080
  }
}
```

The website Caddy compose must join the same external network:

```yaml
networks:
  default:
  web_proxy:
    external: true
```

Then reload the website Caddy container:

```bash
ssh hillsong-vps "cd ~/websites-thebots && docker compose exec web caddy reload --config /etc/caddy/Caddyfile"
```

## Databases

The one Mongo server holds three databases (`MongoModule`, `scripts/mongo-databases.sh`):

| Database | Holds | Backed up |
|----------|-------|-----------|
| `wabot` | Every company's business data (CRM, conversations, persona, agents, bookings, time clock…); each document carries its company's `tenantId` | Yes, `data-<timestamp>.archive.gz`; one company can be restored alone |
| `wabot_platform` | `tenants`, `dashboard_users`, `admin_emails`, `platform_settings`, `tenant_usage` | Yes, `platform-<timestamp>.archive.gz` |
| `wabot_runtime` | `webhook_events`, `scheduler_leases`, `timesheets.challenges` | No: the app can lose them without harm |

Until the database split (the first tag release) everything lived in `wabot`. At its first start the
split release copies the platform and runtime collections into their new databases and leaves the
originals in `wabot`, where the backups skip them. They stay so that a rollback to the release before
the split still finds its data, and any platform change that release makes is copied over again when
the split release starts next. A platform change made on the split release is lost by rolling back
past it, so roll back past the split only when the split release itself is broken.

Once the split release has run well for a while (a week or so), Rodrigo may drop the originals. Only
when he asks; after that, rolling back past the split is no longer possible:

```bash
ssh hillsong-vps 'cd ~/whatsapp-bot && ./backup-mongo.sh && docker compose -f docker-compose.prod.yml exec -T mongo mongosh --quiet wabot --eval "[\"tenants\", \"dashboard_users\", \"admin_emails\", \"platform_settings\", \"tenant_usage\", \"webhook_events\", \"scheduler_leases\", \"timesheets.challenges\"].forEach(c => print(c, db.getCollection(c).drop()))" </dev/null'
```

Look at platform records in `wabot_platform` (`mongosh wabot_platform`), not in `wabot`: until they
are dropped, the originals there are stale copies.

## Backups

MongoDB is the only durable store (users, conversations, CRM clients/quotes/
invoices/payments, tenant config). There is no managed-DB automatic backup —
`scripts/backup-mongo.sh`, `scripts/restore-mongo.sh` and `scripts/restore-tenant.sh` are the whole
story. They read the database names from `scripts/mongo-databases.sh`, which CI copies next to them.

### One-time setup on the VPS

```bash
ssh hillsong-vps "chmod +x ~/whatsapp-bot/backup-mongo.sh"
ssh hillsong-vps "crontab -l 2>/dev/null; echo '17 3 * * * cd ~/whatsapp-bot && ./backup-mongo.sh >> backup.log 2>&1'" | ssh hillsong-vps "crontab -"
```

This writes two gzipped `mongodump` archives to `~/whatsapp-bot/backups/` nightly,
`platform-<timestamp>.archive.gz` and `data-<timestamp>.archive.gz`, and prunes local
copies older than 14 days (`RETENTION_DAYS`), the `mongo-<timestamp>.archive.gz` ones
from before the split included. It refuses to run when `wabot_platform` has no tenants:
that means the app running is older than the split and needs its own release's script.

Status: installed in root's crontab on `hillsong-vps` since 2026-09-28, with
`APP_DIR=/root/whatsapp-bot` set explicitly in the cron line. The first archive
was restore-tested into a throwaway container.

### Off-box copy (Google Cloud Storage)

Since 2026-09-28 every archive is also copied to `gs://thebotslab-backups/mongo/`
(Firebase/GCP project `thebotslab`, billing account "Minha conta de faturamento",
€5 budget alert). The cron line sets `RCLONE_REMOTE=gcs:thebotslab-backups/mongo`.

- Bucket: `europe-west1`, private (uniform access, public access prevention),
  objects deleted after 90 days, 7-day soft delete.
- The VPS authenticates as `vps-backup@thebotslab.iam.gserviceaccount.com` with a key
  at `~/whatsapp-bot/secrets/gcs-backup.json` (mode 600). That account has only
  `storage.objectCreator` + `storage.objectViewer` on this bucket: it can upload and
  read but **not delete**, so a compromised VPS cannot wipe the off-box copies.
- rclone (Ubuntu package) remote `gcs` lives in `/root/.config/rclone/rclone.conf`
  (`service_account_file`, `bucket_policy_only`, `no_check_bucket`).
- Check it: `gcloud storage ls -l gs://thebotslab-backups/mongo/` (as `abizaria@gmail.com`).
- Rotate the key: create a new one with `gcloud iam service-accounts keys create`,
  replace the file on the VPS, then delete the old key id.

Restoring from the bucket (when the VPS copy is gone): copy the archives back next to the others,
then restore them as below.

```bash
gcloud storage cp "gs://thebotslab-backups/mongo/*-<timestamp>.archive.gz" .
scp ./*-<timestamp>.archive.gz hillsong-vps:~/whatsapp-bot/backups/
```

### Manual backups from the backoffice

The backoffice's **Backups** page lists the archives in `~/whatsapp-bot/backups` (mounted read-only
into the app at `/backups`) and has a **Back up now** button. The app never runs anything on the
host: the button only writes `request.json` into `~/whatsapp-bot/backup-control` (mounted at
`/backup-control`), and `backup-runner.sh`, which CI copies next to the other scripts, picks it up
from cron every minute and runs the same `backup-mongo.sh`, lock included, so it never overlaps the
nightly run. The page reads the outcome back from that folder (`last.json` with the run's archives,
`last.log`) and treats the runner as stopped when its `heartbeat` file is more than three minutes old.
It lists each archive with what it holds (platform, the tenants' business data, or everything for the
ones from before the split). An archive shows as copied off the server when `backup-mongo.sh` left
`<archive>.uploaded` after a successful `rclone copy`; archives made before that marker existed show
"Not recorded".

One-time: add the runner to root's crontab with the nightly job's settings:

```bash
ssh hillsong-vps "crontab -l > ~/whatsapp-bot/crontab.bak-\$(date -u +%Y%m%dT%H%M%SZ) && (crontab -l; echo '* * * * * cd /root/whatsapp-bot && APP_DIR=/root/whatsapp-bot RCLONE_REMOTE=gcs:thebotslab-backups/mongo ./backup-runner.sh >> /root/whatsapp-bot/backup.log 2>&1') | crontab -"
```

The runner prints nothing while there is no request, so `backup.log` only grows when a backup runs.
If the page says the runner isn't installed or stopped checking in, check `crontab -l` and the end
of `backup.log`; a failed run's output is also in `backup-control/last.log`.

### Restoring one company

When one company lost or broke data, restore only that company. Its business data comes back as it
was in the archive; other companies, its tenant record, its sign-ins and its token usage stay as they
are:

```bash
ssh -t hillsong-vps "cd ~/whatsapp-bot && ./restore-tenant.sh backups/data-<timestamp>.archive.gz <company-slug>"
```

It loads the archive into a scratch database, then for each collection deletes the company's
documents and copies its archived ones back, prints the counts before and after, and drops the
scratch database. It also takes a `mongo-<timestamp>.archive.gz` from before the split. A company
with extra companies has a slug for each: restore each one that needs it. Before running it, suspend
the company in the backoffice if it is busy, and pause its agents if it has the Agents module (runs
queued in the archive are queued again). Afterwards use **Reload pipeline** on its record in the
backoffice (or restart the app) so the bot uses the restored persona and chat flows. It asks for the
slug as confirmation.

### Restoring whole databases

```bash
ssh -t hillsong-vps "cd ~/whatsapp-bot && ./restore-mongo.sh backups/platform-<timestamp>.archive.gz backups/data-<timestamp>.archive.gz"
```

Each archive replaces its database's collections; pass only the platform or only the data archive to
restore one of them. A `mongo-<timestamp>.archive.gz` from before the split restores into the new
layout: its platform collections go to `wabot_platform`, its runtime state is skipped. Then restart
the app so it reloads tenants and settings: `docker compose -f docker-compose.prod.yml restart app`.

Destructive (`mongorestore --drop`) — both scripts ask for a typed confirmation before running.

### Verifying backups actually work

A backup nobody has restored is a hope, not a backup. Periodically (e.g.
quarterly) restore the latest platform and data archives into a throwaway local
`mongo` container and sanity-check collection counts against `mocks/mongo/README.md`
patterns, or just confirm `tenants` (platform) and `crm.clients` / `crm.invoices`
(data) document counts look right.

## Uptime monitoring

`scripts/healthcheck-alert.sh` polls `/ready` and posts to
`DEPLOY_ALERT_WEBHOOK_URL` only on state *changes* (up→down, down→up), so it
won't spam. This is independent of deploys — it's what tells you the app
crashed or Mongo became unreachable outside of a deploy.

Not installed yet (as of 2026-09-28), and it will not work as-is: its default
`HEALTH_URL` is `http://localhost:8080/ready` on the host, but
`docker-compose.prod.yml` does not publish port `8080`, so every check would
fail. It first needs to probe the app container's IP the way
`scripts/remote-deploy.sh` does.

### One-time setup on the VPS

```bash
ssh hillsong-vps "chmod +x ~/whatsapp-bot/healthcheck-alert.sh"
ssh hillsong-vps "crontab -l 2>/dev/null; echo '*/5 * * * * cd ~/whatsapp-bot && DEPLOY_ALERT_WEBHOOK_URL=<url> ./healthcheck-alert.sh >> healthcheck.log 2>&1'" | ssh hillsong-vps "crontab -"
```

`scripts/remote-deploy.sh` and `scripts/healthcheck-alert.sh` are copied to
the VPS by the deploy workflow (the "Sync compose file and deploy script" and
"Install host scripts" steps in `deploy.yml`) but the cron entries above are
one-time manual setup — CI does not install cron jobs.

## Google sign-in (Firebase)

`/backoffice` (and `/admin`, which redirects there) signs in with Google through
Firebase Auth, project `thebotslab`. The browser gets a Firebase ID token and posts
it to `POST /admin/auth/google`; the server checks Google's signature, the project,
and that the verified email is allowed, then issues the usual admin JWT. Allowed emails are
`ADMIN_EMAILS` plus the ones added on the backoffice's **Admins** page (Mongo `admin_emails`).
`ADMIN_EMAILS` stays the break-glass list: the page shows those emails but can't remove them.
Removing an added email ends that person's session at their next request, not when the token expires.

The tenant dashboard `/app` uses the same Firebase config (no allowlist): `POST /app/auth/google`
signs a user in with the Google account linked to them, and links it automatically the first time
when the verified Google email equals their dashboard email. Users link/unlink Google, change their
password, or switch to Google only from the account button in the `/app` top bar. `GET /app/auth/config`
shows whether Google is offered. Refused tenant sign-ins are logged by `DashboardAuth`.

- VPS `.env`: `FIREBASE_PROJECT_ID`, `FIREBASE_WEB_API_KEY`, `FIREBASE_AUTH_DOMAIN`,
  `FIREBASE_APP_ID` (public web config, from
  `firebase apps:sdkconfig WEB --project thebotslab`) and `ADMIN_EMAILS`
  (comma-separated). After editing, recreate the app on the deployed tag:
  `cd ~/whatsapp-bot && TAG=$(cat .last-good-tag) docker compose -f docker-compose.prod.yml up -d app`.
- Allowed sign-in domains (Firebase Auth): `thebotslab.pt`, `thebotslab.eu` and their
  `www.` hosts. A new host must be added there first, or sign-in fails with
  `auth/unauthorized-domain`. `localhost` is not allowed.
- Password login is off in production (since 2026-09-28): `ADMIN_PASSWORD_HASH` is not set.
  If Google sign-in ever breaks, set it again in `.env` (see `.env.example` for generating the
  bcrypt hash) and recreate the app with the command above. `GET /admin/auth/config` shows which
  methods are active.
- Refused sign-ins are logged by `AdminAuth` with the reason and email.
- A Google-only tenant user who lost their Google account has no self-service way back (there is no
  backoffice action for it yet). Only when Rodrigo asks: generate a bcrypt hash for a temporary
  password (command in `.env.example`), then on the VPS
  `cd ~/whatsapp-bot && docker compose -f docker-compose.prod.yml exec mongo mongosh wabot_platform --eval 'db.dashboard_users.updateOne({ email: "user@example.com" }, { $set: { passwordHash: "<hash>" }, $unset: { googleUid: 1, googleEmail: 1 } })'`
  (drop the `$unset` to keep the Google link). The user then signs in with that password and changes it.

## Agents and Gmail

Both start off for companies: the Agents module is opt-in per company (backoffice → edit the
tenant → Modules), and the Gmail integration stays hidden until a Google OAuth client is set.
A routine deploy needs none of this.

- **Agents runtime** (optional; defaults in `.env.example`): `AGENTS_TICK_SECONDS` (scheduler
  tick, 30), `AGENTS_LANES` (run workers, 4) and `AGENTS_MAX_CONCURRENT_RUNS_PER_COMPANY` (2).
  The agents scheduler, the Gmail sync and the email clean-up each take a Mongo lease
  (`scheduler_leases`) before running.
- **Per company**: the tenants table's **Agents** action (once a company has Agents on) shows its
  runs of the last 7 days, pauses or resumes all its agents, and sets the limits only the platform
  controls (active agents, runs per day, emails per day).
- **Google OAuth client**: a "Web application" client in the Google Cloud project with the
  authorized redirect URI `https://thebotslab.pt/integrations/google/callback`. Set
  `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET` and `GOOGLE_OAUTH_REDIRECT` in the VPS
  `.env`, or in the backoffice's **Platform settings → Google**, which applies them without a
  restart. Consent screen, scopes, verification and the demo video:
  [docs/google-oauth-verification.md](docs/google-oauth-verification.md).
- **`INTEGRATIONS_ENCRYPTION_KEY`** (env only) encrypts the Google tokens kept in Mongo. Generate
  it once with `openssl rand -base64 32`, add it to the VPS `.env`, and keep a copy off the VPS:
  the nightly backup is a `mongodump`, so it holds the tokens but not the key, and without the key
  every account has to be connected again. Without it the app still starts, logs a warning, and
  Gmail can't be connected. Rotate only when Rodrigo asks: put a new key first, then a comma and the
  old one; each account moves to the new key at its next token refresh, and an account that only a
  removed key could open has to be reconnected.
- **Inbox reading** (`GMAIL_INBOX_ENABLED`, default `false`, read at startup) starts the Gmail
  sync job (every `GMAIL_SYNC_SECONDS`, default 60) and lets company admins turn on "Use my inbox
  in automations". Keep it off until Google verified the restricted read scopes and the CASA
  assessment passed. `GMAIL_PUBSUB_TOPIC` is reserved for push and not used yet.
- Email text is dropped 90 days after the email's date. Disconnecting an account deletes its tokens
  and kept mail.
- After editing `.env`, recreate the app on the deployed tag:
  `cd ~/whatsapp-bot && TAG=$(cat .last-good-tag) docker compose -f docker-compose.prod.yml up -d app`.

## Failure Rules

- If the image push fails, check `packages: write` permission and GHCR login.
- If the image pull fails on the VPS, check image name, GHCR login, and tag.
- If SSH deploy fails with missing secrets, add `DEPLOY_HOST`, `DEPLOY_USER`, and `DEPLOY_SSH_KEY`.
- If the app fails to start, inspect app logs first.
- If MongoDB is unreachable, verify `mongo` is running and `MONGO_URI=mongodb://mongo:27017` is effective for the app container.
- Do not delete Docker volumes, reset databases, or rotate secrets unless Rodrigo explicitly asks.
