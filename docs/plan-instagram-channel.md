# Implementation Plan — Multi-Channel Agent (WhatsApp + Instagram)

Status: **Proposed** · Owner: Rodrigo · Last updated: 2026-06-06

## 1. Goal

Let a single tenant's "agent" (LLM + CRM tools + conversation memory) serve users over
**WhatsApp and/or Instagram DM**. A tenant binds one or more external accounts (a WhatsApp
phone number, an Instagram professional account); the bot behind them is the same. A reply
always goes back out on the channel the message arrived on.

Non-goals (for this iteration):
- Unifying a single human's identity across WhatsApp and Instagram (see §9, Decision D1).
- Facebook Messenger, Telegram, or other channels (the abstraction should make them easy later).

## 2. Guiding principle

The agent is **already channel-agnostic**. WhatsApp specifics live only at the two edges:

- **Ingress** — `webhook/WebhookRoutes.kt` parses the WhatsApp payload shape and resolves the
  tenant by `phone_number_id`.
- **Egress** — `whatsapp/WhatsAppClient.kt` sends replies via the WhatsApp Graph endpoint.

Everything between (`MessagePipeline`, `AiClient`, `CrmTools`, the repositories, the rate
limiter) is shared and stays untouched. So this is an **adapter-at-the-edges** change, not a
rewrite. Instagram DMs also ride the Meta Graph API and the same `X-Hub-Signature-256` HMAC
verification, so `WebhookVerifier` is reused as-is.

The plan is **two phases**:

- **Phase A — Refactor (no behavior change).** Introduce the channel abstraction with WhatsApp
  as the only implementation. Fully shippable; WhatsApp behaves identically.
- **Phase B — Add Instagram.** New adapter + tenant binding + config. Drop-in.

## 3. Current WhatsApp-specific surface (what we're abstracting)

| Concern | Location | WhatsApp coupling |
|---|---|---|
| Tenant→channel binding | `tenant/model/TenantModels.kt` | single `phoneNumberId: String` |
| Tenant lookup | `tenant/TenantRegistry.kt` | `byPhoneId` map only |
| Webhook parse | `webhook/WebhookRoutes.kt`, `webhook/dto/WebhookPayload.kt` | `changes[].value.messages[]` shape, `phone_number_id` |
| Inbound DTO | `messaging/MessageQueue.kt` (`InboundMessage`) | `phoneNumberId`, `waId`, `waMessageId` |
| Consumer dispatch | `Application.kt:99` | `tenantRegistry.byPhoneNumberId(inbound.phoneNumberId)` |
| Egress client | `whatsapp/WhatsAppClient.kt` | `/{phoneNumberId}/messages`, global `accessToken` |
| Pipeline egress calls | `messaging/MessagePipeline.kt` (~7 call sites) | `whatsappClient.sendText/uploadMedia/sendDocument` |
| Pipeline build | `tenant/TenantPipelineFactory.kt` | bakes one `WhatsAppClient` per tenant |
| Identity key | `conversation/Repositories.kt`, `conversation/model/DomainModels.kt` | `waId` everywhere |
| Config | `config/AppConfig.kt`, `TenantSeeder.kt` | single `whatsapp.{phoneNumberId,accessToken}` |

## 4. Target abstraction

```
enum class Platform { WHATSAPP, INSTAGRAM }

// One external account bound to a tenant.
data class ChannelBinding(
    val platform: Platform,
    val externalId: String,   // WA: phone_number_id   IG: Instagram account id (entry.id)
    val accessToken: String,  // WA: may fall back to global token   IG: Page access token
)
```

Two new seams:

1. **`ChannelMessageParser`** (ingress) — given the raw webhook body + `object`, produce a list
   of normalized `InboundMessage`. One impl per platform.
2. **`OutboundClient`** (egress) — interface the pipeline talks to instead of `WhatsAppClient`.

```
interface OutboundClient {
    suspend fun sendText(to: String, text: String)
    suspend fun sendDocument(to: String, bytes: ByteArray, filename: String, mimeType: String)
    val capabilities: ChannelCapabilities   // e.g. supportsDocuments: Boolean
}
```

`WhatsAppClient` implements `OutboundClient` (its existing `uploadMedia` + `sendDocument`
collapse behind `sendDocument`). `InstagramClient` is the new impl.

### Per-message egress (important)

A tenant may have **both** channels, so the reply target can't be fixed at pipeline-build time.
`MessagePipeline.handle(...)` must receive the `OutboundClient` for the inbound message's
platform. Recommended shape:

```
suspend fun handle(inbound: InboundMessage, responder: OutboundClient)
```

The consumer (`Application.kt`) resolves the responder from `inbound.platform` + the tenant's
binding before calling `handle`. The pipeline's ~7 `whatsappClient.*` calls become
`responder.*`. The rate limiter, repos, and AI stay shared per tenant — i.e. **one agent, many
channels**, which is the desired model.

## 5. Identity & data model

`waId` is currently the participant key in `users`, `conversations`, `messages` (fields +
indexes + `findByWaId`/`lastNByWaId`/`scoped` filters). Instagram sender IDs (IGSIDs) are a
different namespace, so we must disambiguate.

**Chosen approach (Decision D1 = per-channel identity):** add a `channel: Platform` field and
treat the existing `waId` column as the generic "participant external id". Uniqueness becomes
`(tenantId, channel, waId)`. This keeps the column name (minimal churn across ~30 references)
while making the semantics channel-aware.

Changes:
- `conversation/model/DomainModels.kt` — add `channel: Platform` to `User`, `Conversation`,
  `Message` (default `WHATSAPP` for back-compat).
- `conversation/Repositories.kt` — thread `channel` into `findByWaId`/`findOrCreate`/
  `lastNByWaId` and into `scoped()` so a tenant's WA and IG users never collide.
- Index: replace unique-on-`waId` with compound unique `(tenantId, channel, waId)`
  (`persistence/MongoModule.initialize()`).
- Backfill: existing rows get `channel = WHATSAPP` (see §7).

> If we later want true cross-channel identity (one human, merged history), introduce a
> `Contact` aggregate that links multiple `(channel, externalId)` participants. Out of scope now.

## 6. Phased steps

### Phase A — Channel abstraction, WhatsApp only (ship as-is behavior)

1. **Add `Platform` enum + `ChannelBinding`** in `tenant/model/`.
2. **Tenant model**: replace `phoneNumberId` with `channels: List<ChannelBinding>`. Keep a
   computed helper `fun binding(platform): ChannelBinding?`. Update `TenantRepository` doc
   mapping (read old `phoneNumberId` → synthesize a single WHATSAPP binding for back-compat;
   write `channels` array).
3. **TenantRegistry**: index every binding by `externalId` (keep `byPhoneNumberId` as a thin
   alias that filters WHATSAPP). Add `byExternalId(platform, externalId)`.
4. **`OutboundClient` interface**; make `WhatsAppClient` implement it. Collapse
   `uploadMedia`+`sendDocument` into `sendDocument(bytes, filename, mimeType)`.
5. **`MessagePipeline`**: change `handle(inbound)` → `handle(inbound, responder)`; replace all
   `whatsappClient.*` with `responder.*`. Remove the `whatsappClient` constructor param.
6. **`TenantPipelineFactory`**: stop baking a client into the pipeline. Add
   `responderFor(tenant, platform): OutboundClient` that builds the right client from the
   binding (WhatsApp token falls back to global config token if binding token is blank).
7. **Consumer** (`Application.kt`): resolve `responder = factory.responderFor(tenant, inbound.platform)`
   and call `pipeline.handle(inbound, responder)`.
8. **`InboundMessage`**: add `platform: Platform` (default WHATSAPP). Keep `waId`/`waMessageId`
   field names for now; they hold the WA values. Webhook sets `platform = WHATSAPP`.
9. **Config/seeder**: `TenantSeeder` creates the default tenant with a single WHATSAPP
   `ChannelBinding(externalId = whatsapp.phoneNumberId, accessToken = whatsapp.accessToken)`.
10. **Index migration + backfill** (§7).
11. **Build + run local + smoke test** WhatsApp end-to-end (`/health`, `/ready`, one real DM via
    the Cloudflare tunnel). No user-visible change expected.

> Checkpoint: Phase A is a self-contained PR. Merge before starting Phase B.

### Phase B — Instagram adapter

12. **Webhook ingress**: in `WebhookRoutes.kt`, branch on `payload.object`:
    - `whatsapp_business_account` → existing path.
    - `instagram` → new `InstagramWebhookParser`. IG payload is Messenger-shaped:
      `entry[].id` = IG account id (tenant lookup key), `entry[].messaging[]` with
      `sender.id` (IGSID), `recipient.id`, `message.mid` (dedup eventId), `message.text`.
      Add a new DTO `webhook/dto/InstagramPayload.kt` (don't overload `WebhookPayload`, the
      shapes differ). Normalize to `InboundMessage(platform = INSTAGRAM, waId = sender.id,
      waMessageId = message.mid, ...)`.
    - Skip echoes (`message.is_echo == true`) and read receipts.
13. **`InstagramClient : OutboundClient`** in `whatsapp/` (or a new `instagram/` package):
    - `sendText`: `POST https://graph.facebook.com/{ver}/{igId}/messages` with
      `{"recipient":{"id":<IGSID>},"message":{"text":<text>}}`, Bearer = Page token.
    - `capabilities.supportsDocuments = false` (see constraint below).
    - Honor the **24-hour standard messaging window** (replies allowed within 24h of the user's
      last message; outside it needs message tags). Log/skip gracefully when outside window.
14. **PDF / document delivery on IG** (constraint): Instagram DMs **do not support arbitrary
    document (PDF) attachments** the way WhatsApp does. Options for the quote/invoice flow:
    - (a) Host the PDF (we already persist it under `pdfStoragePath`) and send a **link** in the
      text reply. Cleanest; works today.
    - (b) Render the quote as an **image** and send via IG image attachment.
    Recommend (a) first. The pipeline already branches on created documents — when
    `responder.capabilities.supportsDocuments` is false, send a hosted link instead of calling
    `sendDocument`. This needs a small public URL strategy for stored PDFs (Caddy static route).
15. **Tenant admin**: `admin/TenantAdminRoutes.kt` — allow adding/removing an IG
    `ChannelBinding` (platform, externalId, Page token) to a tenant.
16. **Meta dashboard wiring** (ops, not code): connect the IG professional account to a FB Page,
    subscribe the app's webhook to the `instagram` object `messages` field, grant
    `instagram_manage_messages`, generate the Page access token, store it on the binding.
17. **Build + run local + smoke test** an IG DM end-to-end alongside an unchanged WhatsApp DM on
    the same tenant.

## 7. Migration & backfill

- New compound index `(tenantId, channel, waId)` unique; drop the old `waId` unique index.
- Backfill `channel = "WHATSAPP"` on existing `users`, `conversations`, `messages`
  (extend the existing `TenantSeeder.backfillMissingTenantId` pattern, which already does an
  idempotent `updateMany` over these collections).
- Tenant doc migration: on read, a tenant with legacy `phoneNumberId` and no `channels` array
  synthesizes one WHATSAPP binding; on next write it persists `channels`. Optionally a one-shot
  migration in the seeder.
- `webhook_events` dedup is keyed on `eventId` (message id / mid), which stays globally unique
  per platform — no change needed.

## 8. Config changes (`config/AppConfig.kt`, `application.conf`, `.env`)

- Keep `app.whatsapp.{verifyToken, appSecret, phoneNumberId, accessToken}` — the app secret and
  verify token are app-level and shared (Meta sends both WA and IG webhooks to the same app).
- Per-channel tokens (especially IG Page tokens) live on the **tenant `ChannelBinding`** in
  MongoDB, not in global config — they're tenant-scoped and rotate independently.
- No new required global keys for Phase A. Phase B adds IG bindings via admin API/seed data.

## 8a. Dashboard & onboarding flow

Two dashboards are involved. The Meta one is ops/config; your admin UI needs real code.

### Meta Developer Dashboard (external, one-time per IG account, ops)

1. Convert the target Instagram account to a **Professional** account and connect it to a
   Facebook Page.
2. In the Meta app: add the **Instagram** product, subscribe the webhook to the `instagram`
   object's `messages` field (the webhook URL + verify token are the same app-level values
   already used for WhatsApp — `app.whatsapp.verifyToken`).
3. Grant `instagram_manage_messages` (+ `instagram_basic`, `pages_manage_metadata`). Production
   use typically requires **App Review** for these permissions — factor lead time.
4. Generate a **Page access token** for the linked Page and copy the **Instagram account id**
   (this is the `entry.id` Meta will send in webhooks, and the tenant lookup key).

These two values — `instagramAccountId` + `pageAccessToken` — are what an operator pastes into
*your* admin dashboard. There is no automatic OAuth onboarding in this plan; it's manual paste.
(A future iteration could add Meta Login / Embedded Signup to fetch these automatically.)

### Your admin dashboard (code — backend + frontend)

The current tenant admin is single-channel. Adding an IG account needs:

**Backend (`admin/TenantAdminRoutes.kt`)**
- `TenantCreateRequest` / `TenantUpdateRequest`: replace the single `phoneNumberId` with a
  `channels: List<ChannelBindingDto>` (each = `platform`, `externalId`, `accessToken`). Keep
  accepting a bare `phoneNumberId` for back-compat → map it to one WHATSAPP binding.
- `TenantDto`: expose `channels` (mask/omit `accessToken` in GET responses — never return Page
  tokens to the browser).
- `POST /admin/api/tenants` and `PUT /admin/api/tenants/{slug}`: validate ≥1 binding; on save,
  call `tenantRegistry.put` (re-indexes every binding) and `pipelineFactory.evict` (already
  done today) so the new IG account routes immediately without restart.
- New convenience endpoints (optional but cleaner than full-tenant PUT):
  `POST /admin/api/tenants/{slug}/channels` (add binding) and
  `DELETE /admin/api/tenants/{slug}/channels/{platform}/{externalId}` (remove binding).

**Frontend (`src/main/resources/admin/index.html` + `app.js`)**
- Replace the single "Phone Number ID" input in the tenant create/edit form with a **Channels**
  section: a list of bindings + an "Add channel" control (platform dropdown WHATSAPP/INSTAGRAM,
  External ID, Access Token). Tokens are write-only (blank in edit = unchanged).
- Show each tenant's bound channels (with a platform badge) in the tenant list/detail view.

**Operator flow (end state):** open admin → tenant → Channels → Add channel → pick Instagram →
paste IG account id + Page token → Save. The registry re-indexes and the next IG DM to that
account is handled by the same agent. No redeploy.

> Note: the `agentType` / `openrouterModel` / rate-limit fields stay tenant-level and shared
> across that tenant's channels — i.e. the operator configures the bot once, not per channel.

## 9. Open decisions

- **D1 — Identity model.** Chosen: per-channel identity (§5). Cross-channel merge deferred.
- **D2 — PDF on Instagram.** Recommend hosted-link delivery (§14a). Needs a public static route
  for `pdfStoragePath` behind Caddy. Confirm acceptable vs. image rendering.
- **D3 — Rate limiting scope.** Recommend shared per-tenant across channels (current behavior,
  one `RateLimiter` per pipeline). Alternative: per-channel buckets.
- **D4 — One pipeline per tenant vs. per (tenant, channel).** Recommend per-tenant with a
  per-message `responder` (§4) — keeps "one agent" semantics and shared rate limiting.

## 10. Test plan

- Unit: `InstagramWebhookParser` normalizes a sample IG payload → `InboundMessage`; WhatsApp
  parser unchanged. `OutboundClient` selection by platform.
- Unit: repository `(tenantId, channel, waId)` scoping prevents WA/IG collision.
- Integration (local + tunnel): WhatsApp DM still works after Phase A; IG DM works in Phase B;
  same tenant receives both and each reply returns on its origin channel.
- Regression: CRM quote/invoice flow — WhatsApp still sends the PDF document; IG sends the link.

## 11. Effort estimate (rough)

- Phase A (refactor): ~1–1.5 days. Low risk, mechanical, well-covered by smoke test.
- Phase B (Instagram): ~1.5–2 days code + Meta dashboard/app-review setup (IG messaging
  permissions can require app review for production — factor lead time).
</content>
</invoke>
