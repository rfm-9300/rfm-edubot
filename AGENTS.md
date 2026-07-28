# AGENTS.md

## Deployment Intent

When Rodrigo says any of the following, treat it as permission to execute the full production deployment workflow for this project:

- "make deploy"
- "make deployment"
- "deploy to VPS"
- "make the deployment"
- "deploy"
- any close variant that clearly means deploying this WhatsApp bot to production

Use [DEPLOYMENT_RUNBOOK.md](DEPLOYMENT_RUNBOOK.md) as the source of truth.

## Production Host

- SSH alias: `hillsong-vps`
- Connect with: `ssh hillsong-vps`
- Production app directory on the VPS: `~/whatsapp-bot`
- Production compose file: `docker-compose.prod.yml`
- Production image: `ghcr.io/rfm-9300/whatsapp-bot:${TAG:-latest}`

## Deployment Rules

- Use the existing `./deploy.sh` script from the repo root to build and push the Docker image.
- Use `docker compose -f docker-compose.prod.yml` on the VPS.
- Prefer the safe deploy sequence: `pull`, `down`, `up -d`.
- After deploying, check container state, health endpoints, and logs before reporting success.
- If deployment fails, diagnose the concrete failure, apply the smallest safe fix, then redeploy.
- Do not reset MongoDB volumes, delete data, rotate secrets, or run destructive cleanup unless Rodrigo explicitly asks.
- Do not change `.env` or production secrets unless Rodrigo explicitly asks.
- Keep unrelated local worktree changes intact.

## Command Policy

- General shell commands are allowed when needed to complete the task.
- Read-only `git` commands are allowed for inspection, such as `git status`, `git diff`, `git log`, `git show`, and `git rev-parse`.
- Do not run `git` commands that change commits, branches, refs, the index, remotes, or the working tree unless Rodrigo explicitly asks.

## Commands
- `./gradlew build` — compile + test
- `./gradlew test` — run tests (JUnit 5)
- `./gradlew run` — start app (requires `.env` + MongoDB)
- `./gradlew clean build` — full rebuild

## Local Run Intent

When Rodrigo says "run local", "start local", "run it locally", "start the app", or any close variant, follow [.claude/LOCAL_RUNBOOK.md](.claude/LOCAL_RUNBOOK.md). Confirm MongoDB, app logs, `/health`, and `/ready` before reporting that local is up.

## Mock Data Intent

When Rodrigo says "create-mocks", "create mocks", "seed mocks", or asks to restore mock data after local MongoDB corruption, run the local mock seed workflow from `mocks/mongo/README.md` or the `create-mocks` skill. Use the existing Mongo container/service, run `mocks/mongo/create-mocks.js`, verify the printed counts, and do not drop databases, delete volumes, or touch production data.

## Local Dev Setup
1. `cp .env.example .env` and fill WhatsApp Cloud API + OpenRouter keys
2. `docker compose up -d mongo` — start MongoDB only (port 27017)
3. `./gradlew run` — app on port 8080
4. Expose webhook: `cloudflared tunnel --url http://localhost:8080`
5. Register tunnel URL + verify token in Meta Developer Dashboard

## Architecture
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

## Frontend UI Strings

The product is intended to be multi-language. Do not hardcode user-facing strings — especially not Portuguese-only text — directly in markup or JS render functions.

- New user-facing strings in the frontends (`src/main/resources/app/`, `backoffice/`, `admin/`) must go through a translatable layer (a labels/strings map at the top of the file at minimum, a proper i18n mechanism once one exists), not inline literals.
- When touching code near existing hardcoded strings, prefer lifting them into the strings map rather than adding more inline text.
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
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **rfm-edubot** (2946 symbols, 6122 relationships, 256 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> Index stale? Run `node .gitnexus/run.cjs analyze` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? `npx gitnexus analyze` (npm 11 crash → `npm i -g gitnexus`; #1939).

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows. For regression review, compare against the default branch: `detect_changes({scope: "compare", base_ref: "main"})`.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `query({search_query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `context({name: "symbolName"})`.
- For security review, `explain({target: "fileOrSymbol"})` lists taint findings (source→sink flows; needs `analyze --pdg`).

## Never Do

- NEVER edit a function, class, or method without first running `impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.
- NEVER commit changes without running `detect_changes()` to check affected scope.

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
