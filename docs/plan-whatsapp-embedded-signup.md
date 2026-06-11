# Implementation Plan — Automatic WhatsApp Onboarding (Embedded Signup)

Status: **Proposed** · Owner: Rodrigo · Last updated: 2026-06-10

> Companion to `docs/plan-instagram-oauth-onboarding.md`. WhatsApp linking is currently **manual**:
> an operator pastes a `phone_number_id` into a tenant binding (`POST /admin/api/tenants/{slug}/channels`),
> and the tenant falls back to the **single shared app token** (`TenantPipelineFactory.kt:48`). This plan
> adds a **self-serve flow**: a client clicks "Connect WhatsApp", logs into *their own* Meta business
> account, selects/creates a WABA + phone number, and the backend writes
> `ChannelBinding(WHATSAPP, <phone_number_id>, <their token>)` automatically — no Graph Explorer,
> no shared token, no redeploy.

## 0. Why this is *not* just "the Instagram flow for WhatsApp"

Instagram Login (the flow we shipped in the companion plan) is a **pure server-side redirect**: build an
authorize URL, Meta redirects the browser to `/admin/api/instagram/callback?code=…`, done. WhatsApp has
**no equivalent redirect URL you can link to**. Its self-serve onboarding is **Embedded Signup**, which is
**half client-side**:

- It runs as a **popup driven by Meta's Facebook JS SDK** (`FB.login`, Facebook Login for Business).
- The client logs into *their own* Meta/Facebook business account **inside that popup** and picks or
  creates a WABA + phone number.
- The SDK returns two things to **your frontend** (not to a server callback):
  1. an **authorization `code`** (in the `FB.login` auth response), and
  2. a **`WA_EMBEDDED_SIGNUP` `message` event** carrying `phone_number_id` and `waba_id`.

So this plan needs a **frontend component** (FB JS SDK + a `message` listener), then a backend route that
finishes the handshake. The Instagram plan needed neither. The structural consequences:

| Instagram-Login (shipped) | WhatsApp Embedded Signup (this plan) |
|---|---|
| Server builds an authorize URL; browser redirect | Client-side `FB.login` popup; no redirect URL |
| Unauthenticated callback → needs signed `OAuthState` | Final call is a same-origin POST from the **authenticated** SPA → **no signed state needed** |
| One token endpoint, 60-day token + refresh | Business-integration token, effectively **non-expiring** → no refresh cron |
| One IG account per consent | One WABA + one phone number per session (`sessionInfo`) |
| App-level webhook subscription covers all accounts | Must call `POST /{waba_id}/subscribed_apps` per client WABA |

## 1. Goal

A tenant operator (or the client) connects a WhatsApp business number to a tenant by clicking one button.
The backend completes Embedded Signup: exchanges the `code` for a **business access token**, registers
the phone number, subscribes our app to the client's WABA, and persists
`ChannelBinding(platform = WHATSAPP, externalId = <phone_number_id>, accessToken = <token>)` on the
tenant. The next inbound message to that number is handled by the same agent — no redeploy, and the
tenant no longer rides the shared app token.

Non-goals (this iteration):
- Migrating an **existing** number already on another BSP (the "phone number migration" Embedded Signup
  variant). This plan targets new/owned numbers; migration is a §11 note.
- Background token-refresh cron (Embedded Signup tokens don't expire like IG's; see §9 D3).
- Multi-number WABAs: we bind the **one** `phone_number_id` the session returns. A second number is a
  second Connect (or a later picker, §9 D5).

## 2. Why this over manual paste

| | Manual paste (shipped) | Embedded Signup (this plan) |
|---|---|---|
| Who can do it | Only you, with the `phone_number_id` in hand | The client, self-serve |
| Token used | **Shared** app token (`appConfig.whatsapp.accessToken`) | The client's **own** per-WABA token |
| Number registration / PIN | Done manually outside the app | Handled in-flow (§6.2) |
| Webhook wiring per client | Manual `subscribed_apps` in dashboard | Automatic (§6.2 step 3) |
| Scales to N clients | No (you're the bottleneck) | Yes |
| Meta access bar | Tech Provider + Advanced Access | **Same — unchanged** (§3) |

The shared-token model is the real liability manual paste leaves behind: every tenant sends as the same
WABA, so the WhatsApp client is identical for all of them and there is no per-client isolation, quota, or
revocation. Embedded Signup fixes that by putting a real per-binding token in the slot
`TenantPipelineFactory.kt:48` already prefers.

## 3. Access requirements (heavier than Instagram — read before estimating)

Embedded Signup is gated harder than Instagram Login. To run it **in production** you need, on the Meta
app behind `app.whatsapp.appSecret`:

- **WhatsApp** product added, and an **Embedded Signup configuration** created (yields a `config_id`).
- **Tech Provider** (a.k.a. Solution Partner) posture — Embedded Signup onboards *other* businesses'
  WABAs into your app.
- **Advanced Access** on `whatsapp_business_management` **and** `whatsapp_business_messaging`.
- **Business verification** of your Meta business, and the app out of dev mode for external users.
- **Facebook Login for Business** configured (the JS SDK popup is FB Login under the hood).

For **dev/testing**, Embedded Signup works for users who are **admins/developers/testers of the app**
(App Roles), against test WABAs, without full App Review — the same "be a role on the app" escape hatch
the Instagram plan uses in its §3. Validate the whole pipeline this way before review.

> Reality check: the **code** in this plan is a few days (§14). The **Meta-side gating** (Tech Provider,
> Advanced Access, business verification) is the long pole and is external. Start it in parallel.

## 4. Flow overview

```mermaid
sequenceDiagram
    participant Op as Operator/Client (browser)
    participant App as Admin UI (backoffice app.js)
    participant SDK as FB JS SDK (popup)
    participant API as Backend (WA signup route)
    participant Graph as graph.facebook.com

    Op->>App: Click "Connect WhatsApp" on tenant X
    App->>API: GET /admin/api/whatsapp/embedded-signup/config (admin-jwt)
    API-->>App: { appId, configId, graphVersion, enabled }
    App->>SDK: FB.init(appId) + FB.login({ config_id, response_type:'code' })
    Op->>SDK: log in, pick/create WABA + phone number
    SDK-->>App: message event WA_EMBEDDED_SIGNUP { waba_id, phone_number_id }
    SDK-->>App: FB.login callback { authResponse.code }
    App->>API: POST /admin/api/tenants/{slug}/whatsapp/connect { code, wabaId, phoneNumberId } (admin-jwt)
    API->>Graph: POST /{ver}/oauth/access_token (code -> business token)
    API->>Graph: POST /{phone_number_id}/register (PIN; idempotent)
    API->>Graph: POST /{waba_id}/subscribed_apps (route this WABA to our app)
    API->>Graph: GET /{phone_number_id}?fields=display_phone_number,verified_name (confirm)
    API->>API: ChannelBindingService.upsert(slug, ChannelBinding(WHATSAPP, phoneNumberId, token))
    API->>API: registry re-index + pipeline evict (inside upsert)
    API-->>App: 200 { tenant dto }  -> UI refresh
```

The final leg is an **authenticated POST from our own SPA**, not a browser redirect from Meta — so unlike
the Instagram callback there is **no unauthenticated surface and no `OAuthState`** to build.

## 5. Configuration changes

Embedded Signup reuses the **existing Facebook app** behind `app.whatsapp.appSecret` (same app that owns
the WhatsApp product and the webhook). It adds two **non-secret, frontend-visible** values — the Facebook
**app id** and the Embedded Signup **`config_id`** — plus an API version. Extend `WhatsAppConfig` rather
than adding a new top-level block, since it's the same app:

`config/AppConfig.kt` + `application.conf`:

```hocon
app {
  whatsapp {
    verifyToken = ${?WHATSAPP_VERIFY_TOKEN}
    appSecret   = ${?WHATSAPP_APP_SECRET}
    phoneNumberId = ${?WHATSAPP_PHONE_NUMBER_ID}
    accessToken = ${?WHATSAPP_ACCESS_TOKEN}
    apiVersion  = "v21.0"

    embeddedSignup {
      appId    = ${?WHATSAPP_APP_ID}     # Facebook app ID — PUBLIC, sent to the browser for FB.init
      configId = ${?WA_ES_CONFIG_ID}     # Embedded Signup configuration id — PUBLIC
    }
  }
}
```

```kotlin
data class WhatsAppConfig(
    val verifyToken: String,
    val appSecret: String,
    val phoneNumberId: String,
    val accessToken: String,
    val apiVersion: String = "v21.0",
    val embeddedSignup: EmbeddedSignupConfig = EmbeddedSignupConfig(),
) {
    data class EmbeddedSignupConfig(
        val appId: String = "",
        val configId: String = "",
    ) {
        val enabled: Boolean get() = appId.isNotBlank() && configId.isNotBlank()
    }
}
```

Load `embeddedSignup.appId`/`configId` with `getOptional` (mirror the Instagram block in
`AppConfig.load()`), add them to `logStartupKeys`, and gate on `embeddedSignup.enabled` — blank ⇒ the
config route reports `enabled:false`, the connect route returns 503, and the app still boots on dev
boxes without the values. **`appSecret` stays required and server-side only** (it signs the `code`
exchange and verifies webhooks); never ship it to the browser.

> The Embedded Signup `config_id` and the Facebook `app id` are **not secrets** — they're embedded in
> every Embedded Signup button on the public web. Only `appSecret` and the resulting per-tenant tokens
> are sensitive.

## 6. Backend

New package `whatsapp/signup/` (parallel to `oauth/`):

```
whatsapp/signup/WhatsAppSignupRoutes.kt   // config + connect endpoints (both admin-jwt)
whatsapp/signup/WhatsAppSignupClient.kt   // code->token, register, subscribe, confirm
```

No `OAuthState` analogue and no unauthenticated callback — that's the simplification Embedded Signup buys
by finishing inside the authenticated SPA.

### 6.1 Routes (mounted next to `instagramOAuthRoutes(...)` in `Application.kt:202`)

Both live **inside** `authenticate("admin-jwt")` (contrast the Instagram callback, which had to sit
outside it):

- **`GET /admin/api/whatsapp/embedded-signup/config`** — returns the public values the frontend needs to
  boot the SDK, so the `app id`/`config_id` aren't hardcoded in JS:
  ```json
  { "enabled": true, "appId": "<fbAppId>", "configId": "<configId>", "graphVersion": "v21.0" }
  ```
  When `embeddedSignup.enabled` is false, return `{ "enabled": false }` and the button stays hidden.

- **`POST /admin/api/tenants/{slug}/whatsapp/connect`** — body `{ code, wabaId, phoneNumberId }`.
  Validates the tenant exists, runs the exchange (§6.2), writes the binding (§6.3), returns the updated
  tenant DTO (so the UI refreshes in place). Returns 503 if disabled, 400 on missing fields, 404 if the
  tenant is gone, 502 with a reason if any Graph step fails (no partial binding — §6.2).

### 6.2 Token exchange + activation (`WhatsAppSignupClient`)

Reuse the shared `whatsappHttpClient` (`Application.kt:88`). Every step aborts on non-2xx (log ids only,
never token bodies; return a typed failure; never persist a partial binding):

1. **code → business access token**
   ```
   POST https://graph.facebook.com/{ver}/oauth/access_token
   (form/query) client_id={waAppId} client_secret={appSecret} code={code}
   → { access_token, token_type }      # business-integration token (see §9 D3)
   ```
   This is a **business integration system-user token** scoped to the client's WABA via your app. Unlike
   the IG long-lived token it is effectively non-expiring (revoked only on deauth) — so there is no
   `expires_in` to track and no refresh step.

2. **register the phone number** (required before sending on a fresh number; idempotent)
   ```
   POST https://graph.facebook.com/{ver}/{phone_number_id}/register
   (json) { "messaging_product": "whatsapp", "pin": "<6-digit>" }
   Authorization: Bearer {businessToken}
   ```
   Generate a random 6-digit PIN (this sets the number's two-step-verification PIN). Treat
   *"already registered"* (Graph error subcode for an already-registered number) as **success**, not a
   failure — re-connecting a number must be idempotent. Store the PIN only if you intend to re-register;
   otherwise it's write-once and discardable.

3. **subscribe our app to the client's WABA** (this is what routes their inbound messages to our one
   webhook — the Embedded-Signup analogue of IG's `me/subscribed_apps`)
   ```
   POST https://graph.facebook.com/{ver}/{waba_id}/subscribed_apps
   Authorization: Bearer {businessToken}
   → { success: true }
   ```
   Without this, no messages for that WABA reach us. Non-2xx here **is** fatal (unlike IG's best-effort
   subscribe) — a bound-but-unsubscribed number silently drops every inbound message.

4. **confirm number details** (for display + sanity)
   ```
   GET https://graph.facebook.com/{ver}/{phone_number_id}
       ?fields=display_phone_number,verified_name,quality_rating
   Authorization: Bearer {businessToken}
   ```

Result: `(phoneNumberId, businessToken, wabaId, displayPhoneNumber, verifiedName)`. **Verify during
integration** that `phoneNumberId` equals the `metadata.phone_number_id` Meta puts on inbound webhooks
(`WebhookRoutes.kt:113`) — it is the routing key (§8), so a mismatch means messages won't route.

### 6.3 Writing the binding (reuse — already extracted)

The Instagram plan's §6.4 asked for a shared binding helper; it now **exists** as
`tenant/ChannelBindingService.kt`. Call it directly — do not reinvent:

```kotlin
val updated = bindingService.upsert(
    slug,
    ChannelBinding(Platform.WHATSAPP, phoneNumberId, businessToken),
) ?: return@post call.respond(HttpStatusCode.NotFound)
call.respond(updated.dto())
```

`upsert` already: loads the tenant → replaces any binding with the same `(platform, externalId)` →
persists `channels` + recomputes `phoneNumberId` (`ChannelBindingService.kt:57`) → `registry.remove(old)`
+ `registry.put(updated)` → `pipelineFactory.evict(id)`. So re-connecting the same number just overwrites
the token (rotation) and routing/sending pick the new token up on the next message with zero restart.
This is the **same** helper the Instagram OAuth callback uses (`InstagramOAuthRoutes.kt:73`) — both
channels converge on identical persistence + cache behavior.

> One behavior to note: `ChannelBindingService.persist` sets the tenant's top-level `phoneNumberId` to the
> **first** WHATSAPP binding (`ChannelBinding.kt:57`). With one WhatsApp number per tenant (our case) this
> is exactly right.

### 6.4 Token storage

Identical posture to Instagram (§6.5 there): `ChannelBinding.accessToken` is stored **plaintext** today
(`TenantRepository.toDocument()`), masked to `hasAccessToken: Boolean` in GET responses
(`TenantAdminRoutes.kt:426`). Keep masking; the signup client logs ids/phone numbers only, never token
bodies. Envelope-encryption at rest (the Instagram plan's D2) is the **same** follow-up and should be done
once for both channels at the `TenantRepository` boundary, not twice.

## 7. Egress — no change needed

Unlike the Instagram plan (which had to repoint `InstagramClient` from `graph.facebook.com` to
`graph.instagram.com`), **WhatsApp egress is unchanged**. `WhatsAppClient` already targets
`https://graph.facebook.com/{ver}/{phoneNumberId}` (`WhatsAppClient.kt:52`) and already takes a per-instance
`accessToken` (`WhatsAppClient.kt:46`). The only difference post-Embedded-Signup is that
`TenantPipelineFactory.kt:48` now finds a **non-blank** `binding.accessToken` and uses the client's own
token instead of falling back to `appConfig.whatsapp.accessToken`. Zero code change on the send path.

## 8. Webhooks — no change needed

This is the other big simplification vs. Instagram (whose §8 required a per-`object` signature-secret fix).
WhatsApp Embedded Signup rides the **existing** WhatsApp webhook wiring untouched:

- **Signature:** all Embedded-Signup WABAs send through **your** app, signed with **your**
  `app.whatsapp.appSecret`. `WebhookRoutes.kt:74` already selects that secret for
  `object == "whatsapp_business_account"`. ✔ unchanged.
- **Routing:** inbound messages carry `metadata.phone_number_id`; `WebhookRoutes.kt:118` already resolves
  the tenant via `tenantRegistry.byPhoneNumberId(...)`, and `upsert` indexed the new binding under exactly
  that key (`TenantRegistry.kt:21`). The first message after Connect routes correctly. ✔ unchanged.
- **Verify handshake:** the app-level GET handshake uses `app.whatsapp.verifyToken`
  (`WebhookRoutes.kt:44`) — already configured. ✔ unchanged.

The only ingress dependency is §6.2 step 3 (`subscribed_apps`), which attaches each new client WABA to
this already-configured webhook. No new Meta webhook setup per client.

### 8.1 Account-level events (recommended, additive)

Subscribe the `whatsapp_business_account` object to the **`account_update`** field (Meta dashboard,
one-time) to receive WABA lifecycle events — notably **`PARTNER_REMOVED`** (client revoked your app) and
ban/quality events. Handle `account_update` in `handleWhatsAppWebhook` to **evict the binding** when a
client disconnects (mirror of `InstagramMetaCallbacks` deauthorize → `bindingService.remove`). Without it,
a revoked WABA leaves a dead token + a routing entry that silently fails. This is additive and can ship
just after the happy path.

### 8.2 Data-deletion / deauthorize callbacks

The Facebook app's **Deauthorize** and **Data Deletion Request** callback URLs use the same
`signed_request` mechanism already implemented for Instagram in `oauth/InstagramMetaCallbacks.kt` +
`oauth/SignedRequest.kt`. If/when App Review requires them for the WhatsApp/Facebook app, add
`POST /admin/api/whatsapp/deauthorize` + `/data-deletion` by reusing `SignedRequest` (verify with
`app.whatsapp.appSecret`) and `bindingService.remove`. Structurally identical to the IG callbacks; left
out of the happy-path scope.

## 9. Open decisions

- **D1 — Flow product.** Decided: **Embedded Signup** (FB Login for Business + JS SDK). The only
  self-serve WhatsApp onboarding Meta offers; there is no redirect-only variant.
- **D2 — Token encryption at rest.** Same recommendation and same code site as the Instagram plan's D2 —
  do it **once** for both channels at the `TenantRepository` boundary.
- **D3 — Token expiry/refresh.** Embedded-Signup business-integration tokens are effectively
  non-expiring (revoked on deauth), so **no refresh cron** is needed (contrast IG's ~60d). The failure
  mode is *revocation*, handled by §8.1's `PARTNER_REMOVED`, not expiry.
- **D4 — Who initiates.** Build operator-driven from the backoffice (JWT present). A client-driven public
  tokenized link is a thin later addition — but note it would reintroduce the unauthenticated-surface
  problem and thus need an `OAuthState`-style signed token, exactly like Instagram.
- **D5 — Multi-number WABAs.** Embedded Signup returns one `phone_number_id` per session. Binding the one
  returned covers the common case; a picker over `GET /{waba_id}/phone_numbers` is a later addition.
- **D6 — PIN custody.** We generate the registration PIN (§6.2 step 2). Decide whether to persist it
  (needed to re-register after a number reset) or treat it as write-once. Recommend: persist on the
  binding metadata (encrypted with the token under D2), since losing it blocks re-registration.

## 10. Data model

**No schema migration required** — reuses `ChannelBinding` (`tenant/model/TenantModels.kt:29`) and the
existing channels persistence as-is; `Platform.WHATSAPP` already exists. The same optional, additive,
non-breaking metadata the Instagram plan proposed serves WhatsApp too (default nulls keep old docs valid;
extend `toDocument()`/`getChannels()` in `TenantRepository.kt` and `ChannelBindingDto` if added):

```kotlin
data class ChannelBinding(
    val platform: Platform,
    val externalId: String,             // = phone_number_id for WhatsApp (routing key)
    val accessToken: String = "",
    val displayName: String? = null,    // display_phone_number / verified_name, for admin display
    val wabaId: String? = null,         // needed for subscribed_apps + account-level calls
    val tokenObtainedAt: Instant? = null,
    val source: String? = null,         // "manual" | "embedded_signup"
)
```

`wabaId` is the one genuinely new field worth storing now: §6.2 step 3 and §8.1 both need it, and the
inbound webhook does **not** carry it. Everything else is display sugar.

## 11. Note — phone number *migration* (out of scope)

A client whose number already lives on another BSP can't just register it; Embedded Signup has a
**migration** variant (request a migration code via SMS/voice, then `POST /{phone_number_id}/register`
with `backup`/migration params). This plan targets new/owned numbers. Migration is a contained later
addition to `WhatsAppSignupClient` (an extra branch in step 2) and is flagged here only so it isn't
mistaken for a happy-path requirement.

## 12. Test plan

- **Unit:** `WhatsAppSignupClient` parses sample responses for each of the 4 steps; non-2xx at any step
  aborts without writing; *"already registered"* on step 2 is treated as success; step 3 failure is
  fatal.
- **Unit:** the connect route calls `bindingService.upsert` with `Platform.WHATSAPP` + the returned
  `phone_number_id`, and a re-connect overwrites the token (rotation) and triggers `registry.put` +
  `factory.evict` (these are already covered by `ChannelBindingService`'s behavior — assert the route
  delegates to it).
- **Unit:** config route returns `enabled:false` (and connect returns 503) when
  `embeddedSignup.enabled` is false; `appSecret` is never present in the config response body.
- **Integration (dev mode, app role):** open the backoffice → "Connect WhatsApp" → complete the FB popup
  against a **test WABA** → POST lands → binding written → `GET /tenants/{slug}` shows the WhatsApp channel
  with `hasAccessToken:true` → send a message to the number → inbound routes via `byPhoneNumberId` and the
  reply goes out on the **client's** token (assert it is *not* the shared `appConfig.whatsapp.accessToken`).
- **Negative:** popup cancelled (no `code`) → clean UI error, no POST, no binding; bad `code` → 502 with
  reason, no binding; `subscribed_apps` 4xx → 502, no binding; feature disabled → 503.
- **Regression:** manual "Add channel" paste still works (same `ChannelBindingService.upsert`); Instagram
  OAuth still works; WhatsApp webhooks still verify with `app.whatsapp.appSecret` and route by
  `phone_number_id`; tenants with a **blank** WhatsApp token still fall back to the shared app token
  (`TenantPipelineFactory.kt:48`) so nothing pre-existing breaks.

## 13. Phased steps

1. **Config** — extend `WhatsAppConfig` with `embeddedSignup { appId, configId }` in `application.conf`
   + `AppConfig` (`getOptional` + `enabled` gating), add to `logStartupKeys`. (¼ day)
2. **Signup client** — `WhatsAppSignupClient` (code→token, register w/ idempotent "already registered",
   subscribe-as-fatal, confirm) with error handling + tests. (1 day)
3. **Routes** — `GET …/embedded-signup/config` + `POST …/{slug}/whatsapp/connect`, both under
   `admin-jwt`, delegating the write to the existing `ChannelBindingService`; wire in `Application.kt`
   next to `instagramOAuthRoutes`. (½ day)
4. **Frontend** — load FB JS SDK, "Connect WhatsApp" button (shown only when `enabled`), `FB.login`
   with `config_id` + `response_type:'code'`, `message` listener for `WA_EMBEDDED_SIGNUP` to capture
   `waba_id`/`phone_number_id`, POST to connect, refresh tenant on success
   (`src/main/resources/backoffice/`). (1 day)
5. **Account-level eviction (§8.1)** — handle `account_update` / `PARTNER_REMOVED` in
   `handleWhatsAppWebhook` → `bindingService.remove`. (½ day)
6. **Meta dashboard (ops, one-time)** — add WhatsApp product, create the Embedded Signup configuration
   (`config_id`), configure FB Login for Business, subscribe `account_update`, add test users/WABAs for
   dev-mode validation. (ops)
7. **Build + run local + tunnel smoke test** end-to-end against a test WABA. (½ day)

## 14. Effort estimate (rough)

- Core flow (steps 1–4, 7, happy path): **~3 days** code + Meta dashboard setup.
- Account-level eviction (step 5): **+½ day**. Token encryption (D2, shared with Instagram): **+½–1 day**.
- The dominant cost is **not code** — it's the **Tech Provider / Advanced Access / business verification**
  gating in §3, which is external and slow. Start it in parallel; dev-mode testing (§3) needs no review.
```
