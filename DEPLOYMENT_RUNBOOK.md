# WhatsApp Bot Deployment Runbook

Production deploys of this repo are automated. A merge (or push) to `main`
triggers GitHub Actions: test → build/push the Docker image → SSH to the VPS
and recreate the app container.

Manual `./deploy.sh` + SSH remains an emergency fallback. The phrases
"make deploy" / "deploy to VPS" still mean "get this bot onto production",
but the default path is CI/CD, not a laptop SSH session.

## What this deploy covers

| Surface | Runtime | Deployed by this pipeline? |
|---------|---------|----------------------------|
| Ktor API, WhatsApp/Instagram webhooks | Docker image `ghcr.io/rfm-9300/whatsapp-bot` | Yes |
| Shared dashboard assets `/admin/{asset}` | Static files inside the same image (`style.css`, catalogs). `/admin` redirects to `/backoffice/` | Yes (same container) |
| Tenant dashboard `/app` | Static files inside the same image | Yes (same container) |
| Operator backoffice `/backoffice` | Static files inside the same image | Yes (same container) |
| Website chat widget `/widget`, `/chat/ws` | Same image | Yes (same container) |
| Legal pages `/privacy`, `/data-deletion` | Same image | Yes (same container) |
| MongoDB | Compose service, persistent `mongo_data` | Left running (not recreated unless the mongo image changes) |
| Marketing site `websites-thebots` | Separate VPS compose + Caddy | **No** — different project |
| Mobile Android/iOS | `mobile/` KMP app | **No** — `mobile-ci.yml` tests only; no store deploy |

`/app` and `/backoffice` are not separate services. They are HTML/JS
resources baked into the fat JAR, and they share CSS/i18n served under `/admin/{asset}`.
One image publish updates both surfaces.

## Production Target

- SSH alias (manual fallback): `hillsong-vps`
- Production directory: `~/whatsapp-bot`
- Compose file: `docker-compose.prod.yml`
- Image: `ghcr.io/rfm-9300/whatsapp-bot:${TAG:-latest}`
- Health endpoints: `/health`, `/ready`
- Public hostname: `thebotslab.eu` (Caddy in `websites-thebots`)

## Automated path (merge to main)

Workflow: [`.github/workflows/deploy.yml`](.github/workflows/deploy.yml)

```text
PR / merge to main (excluding mobile-only and docs-only changes)
  → test (./gradlew test)
  → (main only) docker build linux/amd64, push SHA + latest tags to GHCR
  → (main only) scp compose + remote-deploy.sh, SSH pull + up -d
  → curl /health and /ready on the app container
```

Manual re-run: GitHub → Actions → **Deploy WhatsApp Bot** → Run workflow.

Mobile-only changes on `main` do not trigger this workflow (`paths-ignore: mobile/**`).

### One-time GitHub secrets

Add these as repository secrets, or as secrets on the `production` environment:

| Secret | Purpose |
|--------|---------|
| `DEPLOY_HOST` | VPS hostname or IP (the host behind `ssh hillsong-vps`) |
| `DEPLOY_USER` | SSH user that can run Docker in `~/whatsapp-bot` |
| `DEPLOY_SSH_KEY` | Private key whose public key is in that user's `authorized_keys` |
| `DEPLOY_SSH_KNOWN_HOSTS` | Optional. Output of `ssh-keyscan -H <DEPLOY_HOST>`. If unset, CI uses `ssh-keyscan` at deploy time. |

Do **not** put production `.env` values in GitHub. Secrets stay on the VPS.

The workflow authenticates to GHCR with `GITHUB_TOKEN` (no extra registry secret).
It forwards that short-lived token to the VPS only for `docker pull`, then logs out.

Until `DEPLOY_HOST` / `DEPLOY_USER` / `DEPLOY_SSH_KEY` are set, the **Test** and
**Build and push image** jobs can succeed and the **Deploy to VPS** job fails
with a missing-secret error.

### One-time VPS checks

```bash
ssh hillsong-vps "mkdir -p ~/whatsapp-bot && docker network create web_proxy || true"
ssh hillsong-vps "test -f ~/whatsapp-bot/.env && echo '.env present'"
ssh hillsong-vps "curl -fsS --version >/dev/null && echo curl ok"
```

The public GitHub Actions runner must be able to SSH to `DEPLOY_HOST` (firewall
/ security group). Restrict the key to deploy commands if possible.

## Manual fallback

Use only when CI is down or a hotfix must ship from a laptop.

### Preflight

```bash
git status --short
docker info
ssh hillsong-vps "echo ok"
```

Do not require a clean worktree, but do not overwrite unrelated local changes.

### Step 1 - Build and Push Image

```bash
./deploy.sh
```

Default image: `ghcr.io/rfm-9300/whatsapp-bot:latest`

```bash
REGISTRY_IMAGE=ghcr.io/rfm-9300/whatsapp-bot TAG=latest ./deploy.sh
```

### Step 2 - Sync Deploy Files

```bash
ssh hillsong-vps "mkdir -p ~/whatsapp-bot"
scp docker-compose.prod.yml scripts/remote-deploy.sh hillsong-vps:~/whatsapp-bot/
```

Do not copy `.env` unless Rodrigo explicitly asks. Production secrets should already live on the VPS.

### Step 3 - Deploy on VPS

```bash
ssh hillsong-vps "chmod +x ~/whatsapp-bot/remote-deploy.sh && TAG=latest ~/whatsapp-bot/remote-deploy.sh"
```

`scripts/remote-deploy.sh` pulls the image and runs `docker compose up -d --remove-orphans`.
It does **not** `compose down` the whole stack, so Mongo is not bounced on every deploy.

Emergency full recreate (still does not delete volumes):

```bash
ssh hillsong-vps "cd ~/whatsapp-bot && docker compose -f docker-compose.prod.yml pull && docker compose -f docker-compose.prod.yml down && docker compose -f docker-compose.prod.yml up -d"
```

### Step 4 - Verify

```bash
ssh hillsong-vps "cd ~/whatsapp-bot && docker compose -f docker-compose.prod.yml ps"
ssh hillsong-vps "cd ~/whatsapp-bot && docker compose -f docker-compose.prod.yml logs --tail=100 app"
```

Port `8080` is not published on the host. Probe the container IP (the remote
script already does this) or:

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
thebotslab.eu {
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

## Failure Rules

- If the image push fails, check `packages: write` permission and GHCR login.
- If the image pull fails on the VPS, check image name, GHCR login, and tag.
- If SSH deploy fails with missing secrets, add `DEPLOY_HOST`, `DEPLOY_USER`, and `DEPLOY_SSH_KEY`.
- If the app fails to start, inspect app logs first.
- If MongoDB is unreachable, verify `mongo` is running and `MONGO_URI=mongodb://mongo:27017` is effective for the app container.
- Do not delete Docker volumes, reset databases, or rotate secrets unless Rodrigo explicitly asks.
