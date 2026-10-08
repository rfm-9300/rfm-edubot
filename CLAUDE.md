# CLAUDE.md

## Personal wiki (second brain)

Rodrigo keeps a compiled knowledge wiki, a git repo at `/Users/rodrigomartins/projects/my-wiki`
whose remote is the private GitHub repo `rfm-9300/my-wiki`. Below, `<vault>` means that path.
Canonical protocol: `<vault>/ops/bootstrap-prompt.md` (that file wins if this section drifts).

If that path doesn't exist (Cursor cloud agents, other machines), `<vault>` is the `my-wiki`
checkout next to this repo (a Cursor multi-repo environment clones it), or a fresh
`git clone https://github.com/rfm-9300/my-wiki.git`. If neither works, tell Rodrigo and carry on
without the wiki.

### Consult before substantial work

1. Sync first: `git -C <vault> pull --rebase --autostash`.
2. Read `<vault>/wiki/index.md` — one line per page.
3. Open a page only when its index line is clearly relevant. Never bulk-read.
4. Applicable pages are **binding instructions**, not suggestions.

**This repo — start here when the index line matches the task:**

- `wiki/entities/whatsapp-bot.md` — this product (Ktor JVM server + web UI)
- `wiki/entities/whatsapp-bot-mobile.md` and `wiki/notes/kmp-engineering-guide.md` — **binding** when touching `mobile/`
- `wiki/concepts/thebots-design-system.md` — admin/app/backoffice CSS
- `wiki/notes/project-landscape.md` — shared `hillsong-vps`

### Keep the wiki current

Chat is ephemeral; the wiki is the compounding layer. When this session produces durable
knowledge (architecture decisions, cross-repo conventions, gotchas, "why we do it this way"):

1. Check the index — update an existing page if one exists; otherwise file a note via
   `<vault>/ops/workflows/file-note.md`.
2. Write inside `<vault>`. Always bump `wiki/index.md` and append `wiki/log.md`. Never touch `raw/`.
3. Commit only the vault files you changed and push to `main` (if the push is rejected, pull
   with `--rebase` and push again). Unpushed edits are invisible to other machines and cloud
   agents. Committing and pushing the vault is part of filing, even where this repo restricts
   its own git commands.
4. **Do not file:** one-off bugfixes, secrets, deploy credentials, or commands that belong
   in this `AGENTS.md` (the repo operating manual).
5. If unsure whether it belongs, tell Rodrigo instead of writing.

When the session cwd is the vault itself, follow that vault's `AGENTS.md`.

## Deployment Intent

Merges to `main` deploy the Ktor app (including `/app`, `/backoffice`, and shared `/admin` assets) via GitHub Actions. See `DEPLOYMENT_RUNBOOK.md`. Mobile is not deployed. The marketing site `websites-thebots` is a separate VPS project.

When Rodrigo says "make deploy", "make deployment", "deploy to VPS", "make the deployment", "deploy", or any close deployment variant, follow `DEPLOYMENT_RUNBOOK.md`. Prefer confirming or re-running the **Deploy WhatsApp Bot** workflow. Use `./deploy.sh` + `ssh hillsong-vps` only as a fallback if CI cannot publish or SSH.

### Production Host
- SSH alias: `hillsong-vps`
- Connect with: `ssh hillsong-vps`
- Production app directory: `~/whatsapp-bot`
- Production compose file: `docker-compose.prod.yml`
- Production image: `ghcr.io/rfm-9300/whatsapp-bot:${TAG:-latest}`

### Deployment Guardrails
- Do not reset MongoDB volumes, delete data, rotate secrets, or run destructive cleanup unless Rodrigo explicitly asks.
- Do not change `.env` or production secrets unless Rodrigo explicitly asks.
- Keep unrelated local worktree changes intact.
- Do not bounce Mongo with `compose down` on a routine deploy.

## Commands
- `./gradlew build` — compile + test
- `./gradlew test` — run tests (JUnit 5)
- `./gradlew run` — start app (requires `.env` + MongoDB)
- `./gradlew clean build` — full rebuild
- `scripts/persona-e2e/` — browser walkthrough of the Persona page: start `python3 scripts/persona-e2e/fake-openrouter.py`, run the app with `OPENROUTER_BASE_URL=http://127.0.0.1:8099/api/v1` and a backoffice password, then in that folder `npm install && ADMIN_PASSWORD=… WA_APP_SECRET=… node walkthrough.mjs` (exits non-zero on a failed check)

## Local Development - "run local"

When Rodrigo says "run local", "start local", "run it locally", "start the app", or any close variant, follow `.claude/LOCAL_RUNBOOK.md`. Those phrases grant permission to start MongoDB with Docker Compose, run the Ktor app with Gradle, watch logs, and verify `/health` and `/ready`.

## Mock Data - "create-mocks"

When Rodrigo says "create-mocks", "create mocks", "seed mocks", or asks to restore mock data after local MongoDB corruption, use `.claude/skills/create-mocks/SKILL.md` or `mocks/mongo/README.md`. Run `mocks/mongo/create-mocks.js` against the local Mongo container, verify the printed counts, and do not drop databases, delete volumes, or touch production data.

## Local Dev Setup
1. `cp .env.example .env` and fill WhatsApp Cloud API + OpenRouter keys
2. `docker compose up -d mongo` — start MongoDB only (port 27017)
3. `./gradlew run` — app on port 8080
4. Expose webhook: `cloudflared tunnel --url http://localhost:8080`
5. Register tunnel URL + verify token in Meta Developer Dashboard
6. Optional, without an OpenRouter key: start `python3 scripts/assistant-e2e/fake-openrouter.py`, set `OPENROUTER_BASE_URL=http://127.0.0.1:8099/api/v1`, and `node scripts/assistant-e2e/walkthrough.mjs` walks the AI Assistant page in Chrome

## Architecture
See `docs/architecture.md` for full diagrams and component breakdown.

WhatsApp AI Bot — Ktor 3.x server receives Meta webhooks, enqueues messages to a Kotlin Channel, processes async via MessagePipeline (dedup → user/convo lookup → rate limit → LLM via OpenRouter → reply via WhatsApp Graph API → persist to MongoDB).

### Package boundaries
- `webhook/` — HTTP edge: signature verification, webhook GET/POST routes
- `messaging/` — MessageQueue (Channel), MessagePipeline orchestrator, DeduplicationService
- `conversation/` — User/Conversation/Message repositories + domain models
- `ai/` — OpenRouter client with retry + fallback model
- `whatsapp/` — outbound Graph API client
- `ratelimit/` — in-memory token bucket (per-hour + per-day)
- `persistence/` — MongoDB wiring + index creation
- `shared/` — Clock, Ids, Result/AppError sealed classes
- `plugins/` — Ktor: Monitoring, Serialization, StatusPages

### Key patterns
- Webhook POST returns 200 OK immediately; processing is async via Channel consumer
- Deduplication via `webhook_events` collection (unique index on `eventId`)
- Pipeline marks events `processed` or `failed` after handling
- `Application.kt` has `main()` and `Application.module()` — both delegate to `bootstrapModule()`
- Config via HOCON (`application.conf`) with `${?ENV_VAR}` fallbacks
- Fat JAR output: `build/libs/app.jar` (Dockerfile multi-stage, distroless-style)
- MongoDB indexes created at startup in `MongoModule.initialize()`

## UI Design System

When adding or changing web UI (`src/main/resources/admin/`, `app/`, `backoffice/`), follow [`design-system/AGENTS.md`](design-system/AGENTS.md). Admin, app, and backoffice share one stylesheet (`admin/style.css`) and one token/component vocabulary. Do not invent a parallel visual language.

## Frontend UI Strings

The product is intended to be multi-language. Do not hardcode user-facing strings — especially not Portuguese-only text — directly in markup or JS render functions. Details: [`design-system/i18n.md`](design-system/i18n.md).

- New user-facing strings in the frontends (`src/main/resources/app/`, `backoffice/`, `admin/`) must go through the shared catalogs (`admin/catalog.en.js`, `catalog.pt.js`, `catalog.es.js`) and `I18N`, not inline literals.
- When touching code near existing hardcoded strings, prefer lifting them into the catalogs rather than adding more inline text.
- Placeholder/"coming soon" copy counts as a user-facing string — same rules apply.

## Documentation

- Keep Mermaid diagrams updated
- Update `docs/architecture.md` after major structural changes

## Stack
- Kotlin 2.0.21, JVM 20
- Ktor 3.0.1 (Netty server, CIO client)
- MongoDB Kotlin coroutine driver 5.2.1
- kotlinx.serialization, kotlinx-datetime, kotlinx-coroutines
- Logback + logstash-logback-encoder (JSON structured logging)
- Docker Compose: app + mongo:7 + mongo-express (dev only)

<!-- gitnexus:start -->
<!-- gitnexus:keep -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **rfm-edubot** (7817 symbols, 17786 relationships, 300 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> Index stale? Run `node .gitnexus/run.cjs analyze` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? `npx gitnexus analyze` (npm 11 crash → `npm i -g gitnexus`; #1939).

## Always Do

- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `query({search_query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `context({name: "symbolName"})`.
- For security review, `explain({target: "fileOrSymbol"})` lists taint findings (source→sink flows; needs `analyze --pdg`).

## Never Do

- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/rfm-edubot/context` | Codebase overview, check index freshness |
| `gitnexus://repo/rfm-edubot/clusters` | All functional areas |
| `gitnexus://repo/rfm-edubot/processes` | All execution flows |
| `gitnexus://repo/rfm-edubot/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->
