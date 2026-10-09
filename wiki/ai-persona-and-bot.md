---
updated: 2026-10-09
---

# Customer-facing bot and persona

How the bot answers customers on WhatsApp, Instagram and the website: the persona that shapes it, the tools it gets, and the fixes that explain today's prompts. The team's dashboard copilot is a separate module: [ai-assistant.md](ai-assistant.md).

## Persona studio: settings, history, safe synthesis, handoff (PR #54, shipped 2026-10-08, `131ba11`)

A Cursor cloud agent's PR (branch `cursor/persona-studio-hardening-8370`, opened 2026-10-08). Rodrigo
ranked it the most important of the three agent PRs merged that day. The persona (how the bot talks to
customers) was one free-text file synthesized from notes. It is now structured settings beside those
instructions, with versions, a background synthesis that can't wipe it, and an optional handoff to a
person. Checked in the code when it merged:

- **One prompt for every customer chat and the test chat.** `PersonaPrompt` builds the system
  messages for WhatsApp, Instagram and website chats and for the dashboard's test chat, so a test is
  faithful. It always adds `SystemPrompts.CUSTOMER_GUARDRAILS`: before #54 the platform guardrails
  were missing whenever a company had a persona (`SystemPrompts.V1` was unused). The message being
  answered no longer reaches the model twice. Agents' "Write with AI" steps that follow the Persona
  also get its rules and form of address.
- **Settings (`behavior`):** bot name, language (the customer's, or a fixed one, optionally strict),
  tone, form of address, reply length, emoji, greeting, rules and handoff. With none set, the model
  reads what it read before.
- **Storage:** `tenant_persona` (one per company: instructions, `behavior`, version, token estimate,
  status, last error, `stale`), `persona_sources` (notes and uploaded files' text;
  `compiledIntoVersion` is null while pending) and `persona_versions` (a snapshot after each change,
  the last 50 per company, `KEPT_VERSIONS`). An existing persona's text becomes a baseline version on
  its first change, so there was no migration.
- **Synthesis can't lose the persona.** It resumes at boot (`PersonaCompiler.resumePending`; the status
  used to stay `COMPILING` after a deploy), never replaces a persona with an empty answer, batches large
  sources and then condenses or trims, starts again from a hand edit made while it ran, and is metered
  against the monthly token budget under a new usage source, `persona`. `POST /app/api/persona/rebuild`
  answers `202` and runs in the background; with no sources it answers `400 no_sources`. Mongo
  pipeline updates use `$literal`, so text starting with `$` stays text.
- **Human handoff (opt-in in the settings):** the bot gets the `handoff_to_human` tool. Replies pause
  in that chat, the team gets a `conversation_handoff` bell notification that opens it, and the chat
  stays in Needs reply until a person writes. Never on website chats (`PersonaPrompt.handsOff`
  excludes `Platform.WEB`), because the inbox can't answer them. The test chat only notes a handoff.
- **Who:** admins change it; members read it and use the test chat (`canEdit` on the response).
  Errors are `{error, field, limit}` instead of 500s. Uploads take DOCX (DTDs off; an XXE probe is
  tested) and CSV, with size and count limits and a legacy-encoding fallback.
- **Page:** Behavior · Knowledge · Instructions · History tabs beside a sticky test chat that uses
  unsaved edits, a "What the bot reads" preview, and version view and restore.
- **`OPENROUTER_BASE_URL`** (optional; default `https://openrouter.ai/api/v1`) points model calls at
  another OpenAI-compatible endpoint. #54 and #55 carried the identical commit. Both PRs' browser
  walkthroughs (`scripts/persona-e2e/`, `scripts/assistant-e2e/`) run the app against a deterministic
  `fake-openrouter.py` on port 8099; the repo AGENTS.md has the commands.
- **Mobile follow-up:** the app's Persona screen still offers editing to members, who now get `403`;
  it could hide it with `canEdit` ([mobile](mobile.md)).

Merged first and unchanged: it was based on the `main` of the day and CI was green. CI deployed
`131ba11`, health ok, 0 `ERROR` lines.

## Gaps found in a full end-to-end pass (2026-09-11)

- **Fixed** (was reported here as a gap, now closed): the customer-facing bot answered pricing
  questions from the model's general knowledge instead of the tenant's real `crm.standard_items`
  catalog. Root cause was narrower than it looked — `MessagePipeline` already has a
  `list_standard_items` tool wired via `CrmTools`, but two things kept it from firing on a plain
  "what does X cost" message: (1) `shouldUseCrmTools()`'s keyword gate (which decides whether
  tools are even offered to the model that turn) had no price/catalog words — added
  preço/preco/custa/custo/valor/produto/catálogo/disponível/estoque/comprar etc.; (2) even with
  tools offered, nothing forces their use on a read (only confirmed writes get `forceToolUse`), so
  `SystemPrompts.CRM_V1`'s `list_standard_items` line now explicitly says use it for *any*
  price/availability question — staff or customer — and never state a price from memory. Verified
  live: the web widget now answers a catalog item's exact price (confirmed via server logs showing
  the `list_standard_items` tool call and result). Internal quote-creation flow re-tested, still
  works.
- **Fixed** (2026-09-11, same day as reported): the confirm-then-create quote/invoice flakiness.
  Each inbound message is a fresh `MessagePipeline.handle()` call whose context is rebuilt from the
  last 10 plain-text messages (`buildContext`) — tool-call results (e.g. a client's real ObjectId
  from `search_clients`) from the *previous* turn aren't persisted anywhere. On the "pode gerar"
  confirmation turn the model would sometimes call `create_quote` straight away with a fabricated
  placeholder id (e.g. `"CLIENT_ID_DO_ACME"`) instead of re-resolving it, failing with "Client not
  found for reference: ...". Fix was prompt-only (no context-persistence change): `CRM_V1` now
  explicitly says ids don't carry over between turns and to call `search_clients`/`list_quotes`
  again before any write if no real id is visible in the current turn; the generic tool-failure
  message that goes back to the model was also made specific for "not found for reference" errors,
  naming the exact lookup tool to call before retrying. Verified live end-to-end (same repro as
  before: summary → "pode gerar" → quote created on the first try, no retry needed).
- Fixed: the dashboard Conversations inbox (`app/app.js`) filtered out the `WEB` channel
  entirely, so widget conversations were persisted (Mongo) but never visible or selectable
  anywhere in the operator UI. Now shown read-only (composer replaced with a note) since the
  backend already refuses operator sends on `WEB` conversations (ephemeral WS session, no
  send-later API) — see `DashboardRoutes.kt` `/conversations/{id}/messages`.
- Fixed several `app/app.js` copy bugs from reusing one generic string across contexts: "New
  Clients/Quotes/Invoices" buttons (plural nav label reused for a singular action — now use each
  form's own title string), the Quotes "Sent €" stat card that actually summed
  PENDENTE+SENT (relabeled "Open"/"Em aberto" — matches Invoices' pattern of one stat per exact
  status), and the Bookings "Manage services"/"Weekly hours" drawers whose Save buttons and the
  per-service activate/deactivate toggle all said "Save booking"/"Edit booking" regardless of the
  real action.
