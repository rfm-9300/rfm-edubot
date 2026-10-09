---
updated: 2026-10-09
---

# Architecture review (2026-09-28)

A code review of the server and web app: what it found, what was fixed the same day and the design decisions that fix made, and what was left open.

Code inspection at commit `048705e`. **Status:** the first four findings below were fixed the same
day in commit `7b85076` (146 tests green, auth and replay verified against the local app) and
deployed to production through the normal CI run. Design decisions in that change:

- Dashboard access is decided once, in the `jwt("dashboard")` validator, by
  `DashboardAccessPolicy` (`dashboard/DashboardAccess.kt`). The validator stores the resolved
  `DashboardContext` on the call and `call.dashboardContext()` just reads it (no per-handler lookups).
  Tenant users need an `ACTIVE` tenant and an `ACTIVE` user of that tenant; operator impersonation
  also opens `SUSPENDED` tenants (support), never `DELETED`. Login answers `403 account inactive`,
  which the `/app` login screen still shows as the generic invalid-credentials toast.
- Text messages store the queued `InboundMessage` on their `webhook_events` doc (`inbound` field).
  Startup, before routing is installed, re-queues events still `received` from the previous 30
  minutes. The pipeline's user-message insert is `insertIfAbsent` on `(tenantId, waMessageId)`, so a
  message cut off mid-LLM call is answered after restart. Delivery is at-least-once: a crash between
  `sendText` and `markProcessed` can duplicate a reply. Assumes a single app instance.
- The consumer submits work to `ConversationLanes` (16 lanes keyed `tenantId:platform:waId`):
  per-conversation FIFO plus a concurrency cap, at the cost of occasional head-of-line waits.
- `CrmTools.MODULE_OF_TOOL` + `BookingTools.MODULE_OF_TOOL` are the only tool→module tables.
  `list_service_templates` stays on `CATALOG` because `SystemPrompts.crmPromptFor` describes it in
  the catalog block.
- The Mongo shutdown moved to a Ktor `ApplicationStopped` subscriber (Ktor's embedded server
  installs its own JVM shutdown hook).

Original findings (verified in code; the first four are the ones fixed above):

- **Dashboard auth ignores lifecycle status.** `POST /app/auth/login` checks
  `DashboardUser.status` but not `Tenant.status`; `dashboardContext()` (`DashboardRoutes.kt`)
  loads tenant and user on every request but checks neither, and the `jwt("dashboard")` validator
  (`admin/AuthRoutes.kt`) only checks `typ`/`tenantId`. A SUSPENDED/DELETED tenant can still log in
  and use the CRM; a disabled user keeps access until the 24h token expires. The webhook and
  web-chat paths do check `TenantStatus.ACTIVE`.
- **Inbound messages are lost on restart.** Webhook dedup upserts `webhook_events`
  (`status: "received"`, with `rawPayload`), enqueues onto an in-memory unlimited `Channel`, and
  returns 200. Each deploy drops whatever is queued or mid-LLM: Meta does not retry after a 200, and
  a retry would be deduped. Nothing re-processes stale `received` events on startup, although the
  payload needed to replay them is already stored.
- **No per-conversation ordering.** The consumer in `Application.kt` runs
  `launch { pipeline.handle(...) }` per message with no concurrency cap and no per-`waId`
  serialization, so two quick messages from one customer are handled concurrently.
- **The tool→module permission map exists twice and has drifted.** `CrmTools.MODULE_OF_TOOL`
  gates `list_service_templates` by `CATALOG`; `DashboardAssistantToolPolicy.moduleByTool` gates
  it by `QUOTES`.
- **The error contract is lost end to end.** `StatusPages` maps every `Throwable` to 500 (bad
  ObjectIds, enum `valueOf`, `LocalDate.parse`, malformed JSON), and `api()` in both `app.js` and
  `backoffice/app.js` throws `HTTP <status>` without reading the `{error}` body, so users only see
  generic toasts.
- **Change hot spots.** `app/app.js` (3.8k lines, one global `state` with ~50 fields, full
  `innerHTML` re-render plus listener re-binding) was touched in 37 of the last 60 commits;
  `style.css` and each catalog in 34; `DashboardRoutes.kt` (78 endpoints) in 24. Every handler
  repeats `dashboardContext(...)?.takeIf { it.requireModule(X) }` by hand and there are no
  `testApplication` HTTP tests, so a forgotten module gate would not be caught. CRM writes exist
  twice (route handlers and `CrmTools`), and list endpoints resolve the client per row.
- **Gotchas for splitting `app.js`.** `/app/{asset}` and `/admin/{asset}` match a single path
  segment and only map `.js`/`.css` MIME types, so subfolder modules (or `.mjs`) need
  `staticResources` / a `{path...}` route first. No `Cache-Control` or CSP headers are sent.
  `backoffice/app.js` copies about a dozen helpers from `app.js` (Meta SDK loader, embedded signup,
  OAuth popup, `api`, drawer, toast); its `fmtDate` hardcodes `pt-PT`. `doc-template.js` (an IIFE
  exposing only `window.DocTemplate`) is the in-repo precedent for an encapsulated module. The
  i18n catalogs are in full parity (1,249 keys each, no missing references from `app.js`), but
  nothing in CI enforces it.
