# WhatsApp Bot Deployment Runbook

Triggered when Rodrigo says "make deploy", "make deployment", "deploy to VPS", "make the deployment", "deploy", or any close deployment variant.

## Overview

| Component | Runtime | Notes |
|-----------|---------|-------|
| App | Docker image | `ghcr.io/rfm-9300/whatsapp-bot:${TAG:-latest}` |
| MongoDB | Docker Compose | Persistent `mongo_data` volume |
| Website Caddy | Existing VPS container | Reverse proxy and TLS for public access |

## Production Target

- SSH alias: `hillsong-vps`
- Production directory: `~/whatsapp-bot`
- Compose file: `docker-compose.prod.yml`
- Health endpoints: `/health`, `/ready`

## Preflight

Run from the repo root:

```bash
git status --short
docker info
```

Do not require a clean worktree, but do not overwrite unrelated local changes.

Confirm the production host is reachable:

```bash
ssh hillsong-vps "echo ok"
```

## Step 1 - Build and Push Image

Use the repo script:

```bash
./deploy.sh
```

By default this builds and pushes:

```text
ghcr.io/rfm-9300/whatsapp-bot:latest
```

Override with environment variables only when needed:

```bash
REGISTRY_IMAGE=ghcr.io/rfm-9300/whatsapp-bot TAG=latest ./deploy.sh
```

## Step 2 - Sync Deploy Files

Ensure the production directory exists:

```bash
ssh hillsong-vps "mkdir -p ~/whatsapp-bot"
```

Copy non-secret deployment files to the VPS:

```bash
scp docker-compose.prod.yml hillsong-vps:~/whatsapp-bot/
```

Do not copy `.env` unless Rodrigo explicitly asks. Production secrets should already live on the VPS.

## Step 3 - Deploy on VPS

Run:

```bash
ssh hillsong-vps "cd ~/whatsapp-bot && docker compose -f docker-compose.prod.yml pull"
ssh hillsong-vps "cd ~/whatsapp-bot && docker compose -f docker-compose.prod.yml down"
ssh hillsong-vps "cd ~/whatsapp-bot && docker compose -f docker-compose.prod.yml up -d"
```

## Step 4 - Verify

Check container state:

```bash
ssh hillsong-vps "cd ~/whatsapp-bot && docker compose -f docker-compose.prod.yml ps"
```

Check logs:

```bash
ssh hillsong-vps "cd ~/whatsapp-bot && docker compose -f docker-compose.prod.yml logs --tail=100 app"
```

Probe locally on the VPS:

```bash
ssh hillsong-vps "curl -fsS http://localhost:8080/health && echo && curl -fsS http://localhost:8080/ready"
```

Report success only after containers are up and health checks pass.

## Public Admin Routing

The VPS already runs the `websites-thebots-web` Caddy container on ports `80/443`. The bot production compose intentionally does not publish app port `8080`; public traffic reaches it through a private Docker network shared with the website Caddy container.

Create the shared proxy network once:

```bash
ssh hillsong-vps "docker network create web_proxy || true"
```

Add a path route to `/root/websites-thebots/Caddyfile` when exposing the admin panel:

```caddy
thebotslab.eu {
  handle /admin* {
    reverse_proxy whatsapp-bot-app-1:8080
  }

  handle /webhook* {
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

- If the image pull fails, check image name, registry login, and tag.
- If the app fails to start, inspect app logs first.
- If MongoDB is unreachable, verify `mongo` is running and `MONGO_URI=mongodb://mongo:27017` is effective for the app container.
- Do not delete Docker volumes, reset databases, or rotate secrets unless Rodrigo explicitly asks.
