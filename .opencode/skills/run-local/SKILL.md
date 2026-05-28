---
name: run-local
description: Start the WhatsApp bot's local dev environment (MongoDB + Ktor app on :8080), verify it's healthy by tailing logs and probing /health and /ready, and fix common startup failures (port in use, mongo not reachable, missing .env, stale gradle daemon, compile errors). Use this whenever the user says "run it locally", "start the app", "boot the dev env", "is it running", "check the logs", or asks to verify/restart/fix the local stack — even when they don't explicitly name a step.
---

# Run Local

Project-specific runbook for booting and verifying the WhatsApp bot dev stack on this machine. The stack is three processes:

- **MongoDB** — `mongo:7` container on port `27017` (via `docker compose`)
- **Ktor app** — JVM process on port `8080` started by `./gradlew run`
- **Cloudflare quick tunnel** — `cloudflared tunnel --url http://localhost:8080` exposing the Meta webhook URL

Everything in this skill assumes the working directory is the repo root (`/Users/rodrigomartins/projects/Whatsapp-bot`). All paths below are relative to that.

## When to use

Trigger on any of these:
- User asks to start, run, boot, launch, or "spin up" the app/local env
- User asks if the app is running, healthy, up, or working
- User asks to check, tail, or read the logs
- User reports the app won't start, is crashing, or is misbehaving locally
- After making code changes, user wants to verify it still runs

If the user only wants tests, use `./gradlew test` instead — this skill is for the running server.

## Workflow

Follow these phases in order. Don't skip phases — each one's preconditions matter for the next.

### 1. Pre-flight checks

Run these in parallel and report what's missing before doing anything else:

```bash
test -f .env && echo "env: ok" || echo "env: MISSING"
docker info >/dev/null 2>&1 && echo "docker: ok" || echo "docker: NOT RUNNING"
lsof -i :8080 -sTCP:LISTEN -n -P 2>/dev/null | tail -n +2
lsof -i :27017 -sTCP:LISTEN -n -P 2>/dev/null | tail -n +2
```

Decisions:
- **No `.env`** → run `cp .env.example .env`, then **stop and tell the user** which secrets to fill: `WA_VERIFY_TOKEN`, `WA_APP_SECRET`, `WA_PHONE_NUMBER_ID`, `WA_ACCESS_TOKEN`, `OPENROUTER_API_KEY`. The app will start with placeholders but webhook signature verification and LLM calls will fail.
- **Docker not running** → ask the user to start Docker Desktop. Do not try to `open -a Docker` without confirming.
- **Port 8080 in use by an old `java`/`gradle` process from this repo** → kill it (`kill <pid>`). If owned by something else, stop and ask.
- **Port 27017 in use** → likely an old mongo container or a system mongo. Inspect with `docker ps --filter publish=27017` before killing anything.

### 2. Start MongoDB

```bash
docker compose up -d mongo
```

Wait for it to be reachable (don't proceed until this passes):

```bash
until docker compose exec -T mongo mongosh --quiet --eval 'db.runCommand({ping:1}).ok' 2>/dev/null | grep -q 1; do sleep 1; done
```

If it never becomes ready within ~30s, dump container logs: `docker compose logs --tail=100 mongo` and diagnose.

### 3. Start the app

Run `./gradlew run` as a background process so logs can be tailed without blocking. Use the Bash tool's `run_in_background: true`.

```bash
./gradlew run 2>&1
```

Capture the bash shell id — you'll need it to read logs in the next phase.

### 4. Verify startup (the part that matters)

The Ktor app is healthy when you see in the logs:

- `Application started in <N> seconds` — Ktor finished module load
- `Responding at http://0.0.0.0:8080` — listener is bound
- `Mongo indexes ensured` (or similar from `MongoModule.initialize()`) — DB wiring succeeded

Poll the shell output until you see those, or ~60s elapses. Then probe the endpoints:

```bash
curl -fsS http://localhost:8080/health
curl -fsS http://localhost:8080/ready
```

- `/health` returns `{"status":"ok",...}` — app is up
- `/ready` returns `{"status":"ready"}` — Mongo ping succeeded

Both must pass. If `/ready` returns 503, the app is up but can't talk to Mongo — go to the failure table.

### 5. Start or verify Cloudflare tunnel

Always provide Rodrigo with the current Cloudflare URL after the app is healthy. Meta's webhook callback URL for this project is the tunnel base URL plus `/webhook`.

First check whether a tunnel is already running:

```bash
pgrep -fl cloudflared
```

If no tunnel is running, start one in the background and write logs to the shared temp location:

```bash
cloudflared tunnel --url http://localhost:8080 > /var/folders/4w/83nr6q7x7fz3nwg5l_5lzd6c0000gn/T/opencode/whatsapp-bot-cloudflared.log 2>&1
```

Extract the quick tunnel URL from the log:

```bash
rg -o 'https://[-a-zA-Z0-9.]+\.trycloudflare\.com' /var/folders/4w/83nr6q7x7fz3nwg5l_5lzd6c0000gn/T/opencode/whatsapp-bot-cloudflared.log
```

Report both values:

- Cloudflare tunnel base URL: `https://<generated>.trycloudflare.com`
- Meta webhook callback URL: `https://<generated>.trycloudflare.com/webhook`

If the log has an old URL but `pgrep -fl cloudflared` shows no running process, treat the old URL as stale, start a fresh tunnel, and report the new URL only.

### 6. Report

Tell the user concisely:
- Mongo: up (container id) / Ktor: up on :8080
- `/health` and `/ready` results
- Cloudflare tunnel base URL and full Meta webhook callback URL (`/webhook`) — always include these when local is running
- Any warnings spotted in logs (failed signature verifies, OpenRouter errors, rate-limit warns) — surface but don't necessarily fix unless asked

Leave the `./gradlew run` and `cloudflared` background shells alive so the user can keep using them. Tell the user how to stop them (next section).

## Stopping the stack

```bash
# Stop the app: kill the background gradle shell (use the id from phase 3)
# Stop the tunnel: kill the background cloudflared process (use pgrep -fl cloudflared to find it)
# Stop mongo:
docker compose down
```

If `./gradlew run` won't die cleanly, `pkill -f 'GradleWrapperMain\|edubot'` is the hammer.

## Common failures and fixes

| Symptom in logs / output | Likely cause | Fix |
|---|---|---|
| `Address already in use: bind` on 8080 | Stale java process from previous run | `lsof -ti :8080 \| xargs kill`, then re-run |
| `MongoSocketOpenException` / `connection refused` to 27017 | Mongo container not up, or `MONGO_URI` wrong | `docker compose up -d mongo`; verify `MONGO_URI=mongodb://localhost:27017` in `.env` |
| `/ready` returns 503 with mongo error | Mongo started but auth/URI mismatch | Check `.env` `MONGO_URI`, restart app |
| `Could not resolve com.example...` / Gradle dep failures | Network issue or corrupt cache | `./gradlew --refresh-dependencies build` |
| Compile errors on `./gradlew run` | Code is broken | Read the error, fix the file it points at, re-run. Don't suppress with `-x compileKotlin`. |
| App starts but webhook returns 403 on POST | `WA_APP_SECRET` missing/wrong → signature verify fails | Set real value in `.env`, restart app |
| `OpenRouter` 401 errors when a message arrives | `OPENROUTER_API_KEY` missing/wrong | Set real key in `.env`, restart app |
| Gradle daemon hangs / "Daemon will be stopped at the end of the build" loop | Stale daemon | `./gradlew --stop` then retry |
| `JAVA_HOME` errors / wrong JVM version | Project requires JVM 20 | Confirm with `java -version`; advise user to switch JDK (don't auto-install) |

## Boundaries — when to stop and ask

- **Anything destructive on shared state**: don't `docker compose down -v` (deletes the mongo volume), don't `rm -rf build`, don't kill processes you didn't start. Confirm first.
- **Editing `.env`**: only create from `.env.example` if missing. Never overwrite real secrets the user has filled in.
- **Code changes to fix runtime errors**: small obvious fixes (typo in a config key, missing import) are fine. Architectural changes need user buy-in first.
- **If startup fails twice for the same reason after your fix**: stop, summarize what you tried, and ask. Don't loop.

## Quick reference — commands you'll actually run

```bash
# Pre-flight
test -f .env && docker info >/dev/null 2>&1 && echo ok

# Start stack
docker compose up -d mongo
./gradlew run                     # foreground; use run_in_background:true from the Bash tool
cloudflared tunnel --url http://localhost:8080

# Verify
curl -fsS http://localhost:8080/health
curl -fsS http://localhost:8080/ready
rg -o 'https://[-a-zA-Z0-9.]+\.trycloudflare\.com' /var/folders/4w/83nr6q7x7fz3nwg5l_5lzd6c0000gn/T/opencode/whatsapp-bot-cloudflared.log

# Inspect
docker compose logs --tail=100 mongo
lsof -i :8080 -sTCP:LISTEN -n -P
pgrep -fl cloudflared
./gradlew --status

# Stop
docker compose down
./gradlew --stop
```
