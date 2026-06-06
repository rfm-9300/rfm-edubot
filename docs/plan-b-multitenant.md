# Multi-Tenant Architecture Plan

> Transform the current single-tenant bot into a platform where each client gets their own
> isolated bot — different WhatsApp number, different agent behaviour, isolated data — all
> running in **one** deployed app on **one** VPS, behind **one** webhook URL.

This plan is grounded in the actual code as it exists today (package root
`com.rfm.edubot`). Line-level references are given where the change is non-obvious.

---


### one Meta app, many phone numbers

You own the Meta app + WhatsApp Business Account and link each client's phone number under it.
Consequences that shape the rest of this plan:

You have **one** Meta account, **one** app, **one** access token, **one** app secret, **one**
verify token. None of those are per-client. They all stay in `AppConfig`/env exactly as today:

- `appSecret`, `verifyToken`, `accessToken` are **all app-level and shared** → they stay in
  `AppConfig.whatsapp`, unchanged. **The webhook security path and the outbound send auth do not
  change at all.** The single access token sends from any phone number linked under your app.
- The **only** thing that varies per client is `phoneNumberId` — the number you POST to when
  replying, and the routing key for incoming messages.

So the per-tenant WhatsApp identity is exactly **one field: `phoneNumberId`**. `Tenant` carries
no WhatsApp credentials.

---

## Guiding principles

- **No breaking changes during migration.** The existing tenant keeps working at every phase.
- **Each phase compiles and is shippable.** Only Phase 3 touches existing data (one migration).
- **Shared infra stays shared:** `AiClient`, `MongoModule`, `DeduplicationService`, the
  outbound HTTP engine.
- **Per-tenant state is isolated:** `WhatsAppClient` credentials, rate limiter, repositories,
  agent, sequence counters.
- **No new infrastructure** (no Redis, no Kafka). A `ConcurrentHashMap` of ~tens of tenants is
  plenty. Revisit only when the problem actually appears.

---

## Current state (verified against code)

```
Application.kt :: bootstrapModule()                         (single instance of everything)
  ├── AppConfig.whatsapp  → one phoneNumberId/accessToken/verifyToken/appSecret
  ├── one MessageQueue (single Channel) + single consumer loop          (Application.kt:89,108)
  ├── one MessagePipeline  ── NOT just "CRM tools": it directly holds
  │       crmTools, clientRepository, quoteRepository, invoiceRepository,
  │       pdfGenerator, pdfStoragePath, AND hardcodes SystemPrompts.CRM_V1 (MessagePipeline:264)
  ├── one WhatsAppClient (creates its own CIO HttpClient)              (WhatsAppClient.kt:50)
  ├── one AiClient (primaryModel/fallbackModel fixed at construction)  (AiClient.kt:97)
  └── /webhook → verifies against AppConfig.whatsapp.{verifyToken,appSecret} (WebhookRoutes:31,52)

MongoDB "wabot"  — no tenantId anywhere
  users            unique(waId)                               (MongoModule:32)
  conversations    unique(userId), unique(waId)               (MongoModule:36-37)
  messages         unique(waMessageId, partial)               (MongoModule:43)
  webhook_events   unique(eventId), TTL 7d                    (MongoModule:52-55)
  crm.clients      unique(phone)  ← BREAKS multi-tenant       (MongoModule:59)
  crm.quotes       unique(number)                             (MongoModule:65)
  crm.invoices     unique(number)                             (MongoModule:71)
  crm.sequences    unique(name)   ← global ORC/FAT/CLT counters (MongoModule:74)
  crm.standard_items  unique(id), seeded globally             (MongoModule:77, CrmRepositories:288)
```

### Reality check on the pipeline (important)

The previous draft assumed `MessagePipeline` only needed to swap `CrmTools` for an
`AgentDefinition` — "minor change". **That is not true.** The pipeline is deeply CRM-aware:

- Hardcodes `SystemPrompts.CRM_V1` in `buildContext` (`MessagePipeline.kt:264`).
- Owns PDF generation + WhatsApp document upload: `sendCreatedDocuments`,
  `sendLatestQuotePdf`, `savePdf` (`:295-340`).
- Owns CRM heuristics: `shouldUseCrmTools`, `isConfirmationReplyToCrmPrompt`,
  `isContinuingCrmFlow`, `requiresExplicitConfirmation`, `isPdfRequest`, `clientNameRegex`
  (`:363-516`) — all keyed off Portuguese CRM vocabulary.
- Holds `clientRepository`/`quoteRepository`/`invoiceRepository` directly to fetch documents
  for PDF rendering.

So "make the bot pluggable per tenant" is really two separable goals:
1. **Multi-tenancy** (isolation + routing) — mechanical, low-risk, high-value.
2. **Multi-agent** (different behaviours per tenant) — a real refactor of the pipeline.

The recommendation is to **ship multi-tenancy first with the CRM behaviour as the only agent
type**, then extract the agent abstraction. The phase order below reflects that.

---

## Target state

```
Application.kt :: bootstrapModule()
  ├── AppConfig            → app-level only (mongo, openrouter key+default models, admin, pdf, port)
  ├── shared AiClient, DeduplicationService, shared HttpClient engine
  ├── TenantRepository     → CRUD on `tenants` collection
  ├── TenantRegistry       → in-memory cache: phoneNumberId → Tenant, verifyToken → Tenant
  ├── TenantPipelineFactory→ lazily builds + caches one MessagePipeline per tenant
  ├── single MessageQueue + single consumer loop → dispatch by tenant, process concurrently
  └── /webhook (multi-tenant) + /admin (JWT-guarded, tenant-scoped)

MongoDB "wabot"
  tenants            NEW: unique(phoneNumberId), unique(slug)
  users              + tenantId   unique(tenantId, waId)
  conversations      + tenantId   unique(tenantId, waId), unique(tenantId, userId)
  messages           + tenantId   index(tenantId, waId, createdAt), unique(tenantId, waMessageId, partial)
  webhook_events     (eventId stays globally unique — Meta msg ids are global; +tenantId for audit)
  crm.clients        + tenantId   unique(tenantId, phone)
  crm.quotes         + tenantId   unique(tenantId, number)
  crm.invoices       + tenantId   unique(tenantId, number)
  crm.sequences      + tenantId   unique(tenantId, name)   ← per-tenant counters
  crm.standard_items + tenantId   unique(tenantId, id), seeded per tenant
```

---

## New / changed package layout

```
com/rfm/edubot/
  tenant/
    model/TenantModels.kt        NEW  Tenant, TenantStatus
    TenantRepository.kt          NEW  CRUD + indexes
    TenantRegistry.kt            NEW  in-memory cache (phoneNumberId & verifyToken lookups)
    TenantSeeder.kt              NEW  seed first tenant from env on empty collection
    TenantPipelineFactory.kt     NEW  build + cache MessagePipeline per tenant
    TenantContext.kt             NEW  small holder: tenantId passed through the queue
  agent/                          (Phase 5 — multi-agent, optional / later)
    AgentDefinition.kt           NEW  interface: systemPrompt + tools + execute + post-processing
    CrmAgent.kt                  NEW  wraps current CRM behaviour extracted from the pipeline
    AgentRegistry.kt             NEW  "CRM_V1" → factory(mongo, tenantId, deps)
  admin/
    auth/JwtConfig.kt            NEW  sign/verify JWT
    auth/AuthRoutes.kt           NEW  POST /admin/auth/login
    TenantAdminRoutes.kt         NEW  CRUD /admin/api/tenants
    AdminRoutes.kt               CHANGED  tenant-scoped + behind auth
  config/AppConfig.kt            CHANGED  add admin block; keep whatsapp{verifyToken,appSecret,apiVersion} (app-level)
  persistence/MongoModule.kt     CHANGED  new indexes (see Phase 1)
  conversation/Repositories.kt   CHANGED  tenantId in ctor + every filter
  crm/CrmRepositories.kt         CHANGED  tenantId in ctor + every filter + aggregation $match
  messaging/MessageQueue.kt      CHANGED  InboundMessage carries tenantId + phoneNumberId
  messaging/MessagePipeline.kt   CHANGED  tenant-scoped deps (Phase 2); agent-driven (Phase 5)
  webhook/WebhookRoutes.kt       CHANGED  route by phone_number_id; per-tenant signature
  Application.kt                 CHANGED  wire registry + factory + dispatch loop
```

---

## Phase 1 — Data isolation foundation (`tenantId` everywhere)

**Goal:** every document is scoped to a tenant; no query can cross tenants. Do this *first*
so that once tenants exist, isolation is already enforced. Until Phase 4 there is still only
one tenant, so this phase is invisible to users but de-risks everything after it.

### 1.1 Tenant model — `tenant/model/TenantModels.kt`

```kotlin
data class Tenant(
    val id: ObjectId = ObjectId(),
    val slug: String,                     // "construcoes-silva" — unique, URL-safe
    val name: String,                     // "Construções Silva Lda"
    val phoneNumberId: String,            // Meta phone number id — the ONLY per-tenant WA field; unique
    val agentType: String = "CRM_V1",     // selects behaviour (Phase 5)
    val openrouterModel: String? = null,  // optional per-tenant model override
    val rateLimitPerHour: Int = 30,
    val rateLimitPerDay: Int = 200,
    val status: TenantStatus = TenantStatus.ACTIVE,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class TenantStatus { ACTIVE, SUSPENDED, DELETED }
```

> No WhatsApp secrets live on the tenant — `accessToken`, `appSecret`, and `verifyToken` all
> stay app-level in `AppConfig`/env, exactly as today.

### 1.2 `TenantRepository` (follow the existing hand-rolled `Document` mapper style)

Methods: `findByPhoneNumberId`, `findBySlug`, `findAll`, `create`, `update`, `setStatus`.
Mirror the manual `toDocument()/toX()` pattern used in
`CrmRepositories.kt` (the codebase does **not** use POJO codecs for queries).

### 1.3 Add `tenantId` to domain + CRM models

`DomainModels.kt`: add `val tenantId: ObjectId` to `User`, `Conversation`, `Message`.
`CrmModels.kt`: add `val tenantId: ObjectId` to `Client`, `Quote`, `Invoice`.
Update each repository's `toDocument()` / `toX()` mapper to read/write the field.

### 1.4 Scope every repository by `tenantId` (constructor injection)

Change all repos in `conversation/Repositories.kt` and `crm/CrmRepositories.kt` from
`class XRepository(mongoModule)` to `class XRepository(mongoModule, private val tenantId: ObjectId)`,
and make **every** filter `Filters.and(Filters.eq("tenantId", tenantId), <existing filter>)`.
Inserts must set `tenantId`. This is the safest pattern — scoping is enforced in one place
per collection, not at call sites.

Specific spots that are easy to miss:
- `MessageRepository.lastNByWaId` / `lastN` (`Repositories.kt:183,192`) — add tenant filter.
- `UserRepository.findOrCreate` / `findByWaId` (`:30,35`).
- `ConversationRepository.findByWaId` / `findOrCreate` (`:98,108`).
- `ClientRepository.search` blank-query branch returns `Document()` (`:34`) — must become the
  tenant filter, not match-all.
- `QuoteRepository.list` / `findByNumber` / `findById`, `InvoiceRepository.list` / `findById`.
- `StandardItemRepository.seedDefaults` / `search` (`:288,293`).
- **Aggregations:** `QuoteRepository.sumByClient` and `InvoiceRepository.sumByClient`
  (`:131,218`) must prepend a `{$match: {tenantId}}` stage **and** scope the `$lookup` into
  `crm.clients` to the same tenant (use a `pipeline` lookup with a `tenantId` match, or filter
  the joined client afterward). Without this, cross-tenant client names leak into totals.
- `SequenceRepository.next` (`:351`) — key by `(tenantId, name)`, not `name`.

### 1.5 Per-tenant sequence numbering

`crm.sequences` docs become `{ tenantId, name, value }`, upsert filtered on both fields, unique
index on `(tenantId, name)`. Each tenant's `ORC-001` / `FAT-001` / `CLT-001` counters are
independent.

### 1.6 MongoDB indexes — `MongoModule.initialize()`

Replace the single-field unique indexes with tenant-compound ones. **Existing unique indexes
must be dropped first** (wrap each `dropIndex` in try/catch like the existing
`waMessageId_1` handling at `MongoModule.kt:42`):

```
users            drop unique(waId);        create unique(tenantId, waId)
conversations    drop unique(waId),(userId); create unique(tenantId, waId), unique(tenantId, userId)
messages         drop unique(waMessageId); create unique(tenantId, waMessageId) partial(string)
                                            create index(tenantId, waId, createdAt)
crm.clients      drop unique(phone);       create unique(tenantId, phone)
crm.quotes       drop unique(number);      create unique(tenantId, number)
crm.invoices     drop unique(number);      create unique(tenantId, number)
crm.sequences    drop unique(name);        create unique(tenantId, name)
crm.standard_items drop unique(id);        create unique(tenantId, id)
webhook_events   keep unique(eventId) (Meta message ids are globally unique); add index(tenantId)
+ tenants:       unique(phoneNumberId), unique(slug)
```

> `dropIndex` on a non-existent / already-renamed index throws — swallow it, as the code already
> does for `waMessageId_1`. On a fresh DB there's nothing to drop; on the existing prod DB the
> drops run once.

### 1.7 Migration script — `mocks/mongo/migrate-add-tenant-id.js`

Idempotent backfill, run once against prod after the first tenant exists (or as part of the
seeder — see 4.1). Include **all nine** data collections (the previous draft omitted
`crm.standard_items`):

```javascript
const t = db.tenants.findOne();                 // the seeded first tenant
if (!t) throw new Error("seed the first tenant before migrating");
const tenantId = t._id;
[ "users","conversations","messages","webhook_events",
  "crm.clients","crm.quotes","crm.invoices","crm.sequences","crm.standard_items"
].forEach(c =>
  db[c].updateMany({ tenantId: { $exists: false } }, { $set: { tenantId } })
);
```

Run **before** creating the new unique indexes, otherwise the partial backfill state could
violate them. Order: seed tenant → backfill → (re)create indexes.

---

## Phase 2 — Tenant-scoped pipeline construction (still single tenant)

**Goal:** build the existing pipeline *through* a tenant, without changing behaviour. After
this phase, `bootstrapModule` constructs everything from a `Tenant` object instead of
`AppConfig.whatsapp`, but there's still exactly one tenant.

### 2.1 `TenantPipelineFactory` — `tenant/TenantPipelineFactory.kt`

```kotlin
class TenantPipelineFactory(
    private val mongo: MongoModule,
    private val aiClient: AiClient,                  // shared
    private val dedup: DeduplicationService,         // shared
    private val httpEngine: HttpClientEngine,        // shared CIO engine (see 2.3)
    private val appConfig: AppConfig,
) {
    private val pipelines = ConcurrentHashMap<ObjectId, MessagePipeline>()

    fun getOrCreate(t: Tenant): MessagePipeline = pipelines.getOrPut(t.id) { build(t) }
    fun evict(tenantId: ObjectId) = pipelines.remove(tenantId)?.also { /* close WhatsAppClient */ }

    private fun build(t: Tenant): MessagePipeline {
        val clients   = ClientRepository(mongo, t.id)
        val quotes    = QuoteRepository(mongo, t.id)
        val invoices  = InvoiceRepository(mongo, t.id)
        val items     = StandardItemRepository(mongo, t.id).also { runBlocking { it.seedDefaults() } }
        val wa = WhatsAppClient(accessToken = appConfig.whatsapp.accessToken,  // shared, app-level
                                phoneNumberId = t.phoneNumberId,               // the only per-tenant bit
                                apiVersion = appConfig.whatsapp.apiVersion)
        return MessagePipeline(
            users = UserRepository(mongo, t.id),
            conversations = ConversationRepository(mongo, t.id),
            messages = MessageRepository(mongo, t.id),
            rateLimiter = RateLimiter(t.rateLimitPerHour, t.rateLimitPerDay),
            aiClient = aiClient,
            whatsappClient = wa,
            deduplicationService = dedup,
            crmTools = CrmTools(clients, quotes, invoices, items),
            clientRepository = clients, quoteRepository = quotes, invoiceRepository = invoices,
            pdfGenerator = PdfGenerator(),
            pdfStoragePath = "${appConfig.pdfStoragePath}/${t.slug}",   // isolate PDFs per tenant
        )
    }
}
```

Notes:
- PDF storage path is namespaced per tenant (`./data/pdfs/<slug>/...`) so files don't collide.
- `MessagePipeline`'s constructor is unchanged in this phase — we just feed it tenant-scoped
  repositories. (The agent abstraction comes in Phase 5.)

### 2.2 Per-tenant OpenRouter model override

`AiClient` currently fixes `primaryModel`/`fallbackModel` at construction and `complete()`
takes no model arg (`AiClient.kt:118`). To honour `Tenant.openrouterModel` you have two
options:
- **(a) Minimal:** add an optional `modelOverride: String? = null` param to
  `AiClient.complete(...)` and thread it through `executeRequest`. Keeps one shared client.
  *Recommended.*
- **(b) Per-tenant `AiClient`** in the factory — simpler call sites but N HTTP clients.

Go with (a); the pipeline passes `tenant.openrouterModel` (captured at build time) into each
`complete` call.

### 2.3 Share the outbound HTTP engine

Today each `WhatsAppClient` builds its own `HttpClient(CIO)` (`WhatsAppClient.kt:50`). With one
client per tenant that's N connection pools. Refactor `WhatsAppClient` to accept a shared
`HttpClientEngine` (or a shared `HttpClient`). The `accessToken` is shared (app-level); only
`phoneNumberId`/`baseUrl` differ per tenant. Low priority for a handful of tenants, but cheap to
do now and avoids a leak when `evict()` discards pipelines.

---

## Phase 3 — Tenant registry, seeder, and webhook routing

**Goal:** the webhook serves all tenants and routes by `phone_number_id`. This is the phase
that flips the app from "one tenant" to "many".

### 3.1 `TenantRegistry` — `tenant/TenantRegistry.kt`

```kotlin
class TenantRegistry(private val repo: TenantRepository) {
    private val byPhoneId = ConcurrentHashMap<String, Tenant>()

    suspend fun initialize() = repo.findAll().forEach(::put)
    fun byPhoneNumberId(id: String) = byPhoneId[id]
    fun put(t: Tenant) { byPhoneId[t.phoneNumberId] = t }
    fun remove(t: Tenant) { byPhoneId.remove(t.phoneNumberId) }
    suspend fun reload(id: ObjectId) { /* re-read one tenant, re-put */ }
}
```

A single `phoneNumberId → Tenant` map is all routing needs under Option A (verify token is
app-level, checked against `AppConfig`).

### 3.2 `TenantSeeder` — `tenant/TenantSeeder.kt`

On startup, if `tenants` is empty, create the first tenant from the current env var
`WA_PHONE_NUMBER_ID` → `phoneNumberId` (slug e.g. `"default"`). All the WhatsApp secrets
(`WA_ACCESS_TOKEN`, `WA_VERIFY_TOKEN`, `WA_APP_SECRET`) stay app-level in `AppConfig` and are
not copied onto the tenant. This keeps the **existing production bot working with zero config
change** and
gives the migration script (1.7) a tenant id to backfill against. Run order in `main()`:
`mongo.initialize()` → `seeder.run()` → `migrate-add-tenant-id` (one-off) → `registry.initialize()`.

### 3.3 `InboundMessage` carries tenant routing info — `MessageQueue.kt`

```kotlin
data class InboundMessage(
    val tenantId: ObjectId,        // NEW
    val phoneNumberId: String,     // NEW (routing/audit)
    val waId: String,
    val waMessageId: String,
    val profileName: String? = null,
    val messageText: String,
    val timestamp: String,
    val eventId: String,
)
```

### 3.4 Multi-tenant `WebhookRoutes.kt`

The route still needs `AppConfig.WhatsAppConfig` for the **app-level** `verifyToken`/`appSecret`
(`WebhookRoutes.kt:21,31,52`), and *additionally* takes the `TenantRegistry` for routing.

**GET (verification): unchanged from today.**
```
GET /webhook?hub.mode=subscribe&hub.verify_token=X&hub.challenge=Y
  compare X to AppConfig.whatsapp.verifyToken → 200 Y if match else 403   (exactly as now)
```

**POST (messages):** the existing security flow is untouched; only routing is added.
```
1. read raw body
2. verify X-Hub-Signature-256 against AppConfig.whatsapp.appSecret   (unchanged — WebhookRoutes.kt:52)
3. parse payload
4. per change: phoneNumberId = change.value.metadata.phone_number_id  (already parsed: WebhookPayload.kt:33-39)
   tenant = registry.byPhoneNumberId(phoneNumberId)
     → null  → log "unknown number" + skip that change (still 200)
     → status != ACTIVE → skip (still 200)
5. dedup (unchanged) → enqueue InboundMessage(tenantId = tenant.id, phoneNumberId = phoneNumberId, ...)
6. respond 200 OK quickly (unchanged contract)
```

Because there's a single app secret, you verify the signature **before** parsing, exactly as the
code does today — no ordering subtlety. Routing is a pure lookup after the message is already
trusted.

### 3.5 Dispatch loop — `Application.kt`

Replace the single-pipeline consumer (`Application.kt:108-121`) with tenant dispatch:

```kotlin
pipelineScope.launch {
    for (inbound in messageQueue.receiveChannel()) {
        val tenant = tenantRegistry.byPhoneNumberId(inbound.phoneNumberId) ?: continue
        val pipeline = pipelineFactory.getOrCreate(tenant)
        launch {                                  // tenants (and messages) process concurrently
            try { pipeline.handle(inbound) }
            catch (e: Exception) { log.error("pipeline failed tenant={} waId={}", tenant.slug, inbound.waId, e) }
        }
    }
}
```

> Concurrency note: `launch` per message gives parallelism but unbounded fan-out. For a small
> tenant count it's fine. If a single abusive tenant could flood, add a per-tenant
> `Semaphore`/`limitedParallelism` later — not now.

---

## Phase 4 — Admin API: tenant CRUD + auth + tenant-scoping

**Goal:** manage tenants over HTTP, and stop the existing admin UI from being an unauthenticated
cross-tenant data leak.

### 4.1 Two distinct problems with the current admin

`AdminRoutes.kt` is (a) completely unauthenticated and (b) constructed with **one** set of CRM
repositories (`Application.kt:151`). Under multi-tenancy it must become **auth-guarded** *and*
**tenant-aware** — every `/admin/api/*` call needs to know which tenant's data it's touching
(via a `tenantSlug`/`tenantId` path segment or query param), and must build repositories scoped
to that tenant. This was missing from the previous draft and is a real data-isolation hole.

Recommended shape:
```
/admin/api/tenants/{slug}/clients
/admin/api/tenants/{slug}/quotes
/admin/api/tenants/{slug}/invoices
/admin/api/tenants/{slug}/standard-items
```
Resolve `{slug}` → tenant via registry, then build the tenant-scoped repositories per request
(cheap — they're thin wrappers over collections).

### 4.2 Dependencies — `build.gradle.kts`

```kotlin
implementation("io.ktor:ktor-server-auth-jvm")
implementation("io.ktor:ktor-server-auth-jwt-jvm")
```
(`java-jwt` comes transitively via the Ktor JWT artifact.)

### 4.3 `AdminConfig` in `AppConfig` + `application.conf`

```kotlin
data class AdminConfig(
    val jwtSecret: String,         // env ADMIN_JWT_SECRET
    val jwtIssuer: String = "wabot-platform",
    val jwtExpiryHours: Int = 24,
    val adminPasswordHash: String, // env ADMIN_PASSWORD_HASH (bcrypt) — do not store plaintext
)
```
Add `app.admin { ... }` to `application.conf` with `${?ADMIN_*}` fallbacks, mirroring the
existing pattern.

### 4.4 Auth + tenant CRUD routes

```
POST   /admin/auth/login                 { password } → { token, expiresAt }   (unauthenticated)
authenticate("admin-jwt") {
  GET    /admin/api/tenants
  POST   /admin/api/tenants              create → insert + registry.put (+ seedDefaults for items)
  GET    /admin/api/tenants/{slug}
  PUT    /admin/api/tenants/{slug}       update name/model/rate limits/tokens → registry.put + factory.evict
  POST   /admin/api/tenants/{slug}/suspend|activate    → setStatus + registry refresh
  DELETE /admin/api/tenants/{slug}       soft delete (status=DELETED) + registry.remove + factory.evict
  POST   /admin/api/tenants/{slug}/reload→ factory.evict (rebuild on next message)
  ... existing CRM routes, now under /admin/api/tenants/{slug}/...
}
```
Creating a tenant: insert → `registry.put` → pipeline builds lazily on first inbound message.

---

## Phase 5 — Multi-agent abstraction (do this LAST, only when a 2nd behaviour exists)

**Goal:** let different tenants run different bot behaviours, not just the CRM bot. Until you
actually have a second agent type, this phase is pure refactor with no user-visible value —
defer it.

### 5.1 The honest scope

Because the pipeline owns CRM specifics (system prompt, PDF sending, all the
confirmation/keyword heuristics — see "Reality check" above), an `AgentDefinition` that is more
than a thin tool-bag must capture:
- `systemPrompt` (replaces the hardcoded `SystemPrompts.CRM_V1` at `MessagePipeline.kt:264`),
- `toolDefinitions` + `executeTool(call)`,
- **pre-flight hooks**: "should tools be available for this message?", "is this an explicit
  confirmation?" (`shouldUseCrmTools`, `isConfirmationReply*`, `requiresExplicitConfirmation`),
- **post-flight hook**: "given tool results, are there documents/attachments to send?"
  (`sendCreatedDocuments`, `sendLatestQuotePdf`).

```kotlin
interface AgentDefinition {
    val systemPrompt: String
    val toolDefinitions: List<ToolDefinition>
    suspend fun executeTool(call: ToolCall): JsonObject
    fun toolsRelevant(message: String, context: List<ChatMessage>): Boolean
    fun requiresConfirmation(toolName: String): Boolean
    suspend fun afterReply(waId: String, toolResults: List<JsonObject>, wa: WhatsAppClient)
}
```

### 5.2 `CrmAgent` + `AgentRegistry`

Move the CRM heuristics and PDF/document logic out of `MessagePipeline` into `CrmAgent`
(constructed with the tenant-scoped repos + `PdfGenerator` + `pdfStoragePath`). `MessagePipeline`
becomes generic: it orchestrates the AI loop and delegates the CRM-specific decisions to
`agent`. `AgentRegistry` maps `tenant.agentType` ("CRM_V1") → factory, and
`TenantPipelineFactory.build` calls `AgentRegistry.build(t.agentType, mongo, t.id, ...)` instead
of hardcoding `CrmTools`.

### 5.3 Adding a future agent then costs

1. `agent/SupportAgent.kt : AgentDefinition`
2. `AgentRegistry.register("SUPPORT_V1") { ... }`
3. create the tenant with `agentType = "SUPPORT_V1"`

No pipeline/webhook/infra changes.

---

## Implementation order (revised)

```
Phase 1  tenantId on all models + repos + indexes + migration script   (invisible, de-risks all)
Phase 2  build pipeline from a Tenant (factory) + model override + shared HTTP engine
Phase 3  TenantRegistry + TenantSeeder + multi-tenant webhook + dispatch loop   ← goes multi-tenant
Phase 4  Admin: tenant CRUD + JWT auth + tenant-scoped CRM admin
Phase 5  (later) AgentDefinition extraction — only when a 2nd behaviour is needed
AppConfig cleanup  — keep whatsapp{verifyToken,appSecret,apiVersion} app-level; add admin block (Phase 4)
```

Each phase compiles; the seeded "default" tenant keeps the current bot live throughout. The
only data-touching step is the Phase 1 migration (run once, idempotent, after seeding).

---

## What genuinely stays unchanged

| Component | Change |
|---|---|
| `AiClient` HTTP/retry logic | None except an optional `modelOverride` param (2.2) |
| `DeduplicationService` | None — `eventId` (Meta msg id) is globally unique; shared is correct |
| `WebhookVerifier` HMAC | None — just called with a per-tenant `appSecret` |
| `PdfGenerator` | None |
| `SystemPrompts` | None (until/unless Phase 5 makes it per-agent) |
| `RateLimiter` algorithm | None — just instantiated per tenant with tenant limits |
| Dockerfile / docker-compose / deploy.sh | None |
| Webhook 200-OK-immediately contract | None |

## Known limitations / future hardening (out of scope now)

- No WhatsApp secrets are stored in Mongo (all app-level in env), so there's nothing extra to
  encrypt there. The only secret added is `ADMIN_PASSWORD_HASH` (env) — keep it hashed.
- Per-message `launch` is unbounded; add per-tenant concurrency limits if a tenant can flood.
- `RateLimiter` is in-memory per process — correct here (single instance); revisit only if you
  ever run multiple app replicas.
- Admin auth is a single shared admin password; per-operator accounts/roles are a later concern.

---

## Test checklist per phase

- **Phase 1:** unit-test that a repo scoped to tenant A never returns tenant B's docs; sequence
  counters are independent; `sumByClient` totals don't include another tenant's clients.
- **Phase 3:** two fake tenants with different `phone_number_id`; assert a message for one never
  hits the other's pipeline; a payload with an unknown `phone_number_id` is skipped (still 200);
  GET verification and wrong-signature POST still behave exactly as today (single app secret).
- **Phase 4:** unauthenticated `/admin/api/...` is 401; cross-tenant slug access is scoped.
- **Regression throughout:** the seeded "default" tenant reproduces today's behaviour
  end-to-end (incoming text → AI → reply → PDF on quote/invoice).
```
