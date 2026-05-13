# CLAUDE.md

## Deployment Intent

When Rodrigo says "make deploy", "make deployment", "deploy to VPS", "make the deployment", "deploy", or any close deployment variant, follow `DEPLOYMENT_RUNBOOK.md`. Those phrases grant permission to run the full production deployment workflow: execute `./deploy.sh`, connect to `hillsong-vps`, deploy with `docker-compose.prod.yml`, inspect health/logs/status, fix safe deployment issues, and redeploy if needed.

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

## Commands
- `./gradlew build` — compile + test
- `./gradlew test` — run tests (JUnit 5)
- `./gradlew run` — start app (requires `.env` + MongoDB)
- `./gradlew clean build` — full rebuild

## Local Development - "run local"

When Rodrigo says "run local", "start local", "run it locally", "start the app", or any close variant, follow `.claude/LOCAL_RUNBOOK.md`. Those phrases grant permission to start MongoDB with Docker Compose, run the Ktor app with Gradle, watch logs, and verify `/health` and `/ready`.

## Local Dev Setup
1. `cp .env.example .env` and fill WhatsApp Cloud API + OpenRouter keys
2. `docker compose up -d mongo` — start MongoDB only (port 27017)
3. `./gradlew run` — app on port 8080
4. Expose webhook: `cloudflared tunnel --url http://localhost:8080`
5. Register tunnel URL + verify token in Meta Developer Dashboard

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
