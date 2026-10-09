---
updated: 2026-10-09
---

# Product overview

GitHub `rfm-9300/rfm-edubot`, GitNexus index name `rfm-edubot`. A Ktor 3 server that receives Meta WhatsApp Cloud API webhooks, replies through an LLM (OpenRouter) and persists to MongoDB, which has grown into a multi-module CRM. Web UIs: admin, app and backoffice, sharing one stylesheet and the rules in [`design-system/`](../design-system/AGENTS.md). Companion app: [mobile](mobile.md). Structure and diagrams: [`docs/architecture.md`](../docs/architecture.md).

## Runtime shape

Webhook POST returns 200 immediately; work is async on a Kotlin Channel (`MessagePipeline`:
dedup → user/convo lookup → rate limit → LLM → WhatsApp Graph API → Mongo). Dedup via
`webhook_events` (unique `eventId`). Fat JAR `build/libs/app.jar`.

Package boundaries (repo AGENTS.md): `webhook/`, `messaging/`, `conversation/`, `ai/`,
`whatsapp/`, `ratelimit/`, `persistence/`, `shared/`, `plugins/`.

Stack noted in-repo: Kotlin 2.0.21, JVM 20, Ktor 3.0.1, MongoDB coroutine driver 5.2.1.

## Tenant provisioning (since 2026-09-17)

The product is a multi-module CRM, not a WhatsApp-first bot. Two consequences baked into
`DashboardModules` and the backoffice tenant form:

- **Channels are optional.** A tenant can be created with zero `ChannelBinding`s (CRM-only) and
  have WhatsApp/Instagram/web connected later. `validateBindings` only checks the shape of the
  bindings that are present.
- **`overview` is the only always-on module.** `conversations`, `contacts` and `settings` moved
  into `optional` — every module except the landing page can be switched off per tenant.
  `enabledModules = null` still means "full catalog" for legacy tenants.

Module gating is enforced per route (403), not just in the nav.

## Rules that apply everywhere

- User-facing web strings go through i18n catalogs (`catalog.en.js` / `.pt.js` / `.es.js`), not
  inline Portuguese. Details in [`design-system/i18n.md`](../design-system/i18n.md).

- Quote/invoice PDFs live under `PDF_STORAGE_PATH` (`/data/pdfs` on the production `pdf_data`
  volume). Mongo only stores the path. `GET …/invoices/{id}/pdf` (and quotes) regenerates a
  missing file — without the volume, every container recreate 404s until first download.

The Ktor server is **JVM, not KMP**. KMP rules in [KMP engineering guide](kmp-engineering-guide.md) bind the mobile
subproject, not this server.
