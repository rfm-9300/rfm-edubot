---
updated: 2026-10-17
---

# Customer-facing bot and persona

How the bot answers customers on WhatsApp, Instagram and the website: the persona that shapes it, the tools it gets, the chat flows that answer before it, and the fixes that explain today's prompts. The team's dashboard copilot is a separate module: [ai-assistant.md](ai-assistant.md).

## Chat flows: ManyChat-style menus before the AI (PR #74, shipped 2026-10-13)

Rodrigo asked to copy a ManyChat Instagram flow for a company moving off ManyChat: greeting and
topic keywords open a menu, buttons lead through sub-menus to a last message with a link. Nothing in
the product could do that, so `flows/` adds menu flows that answer before the AI.
`docs/architecture.md` → "Chat flows" has the routing. What matters later:

- **Not the Agents module.** An event trigger's keywords are case-insensitive substrings
  (`AgentDispatcher`), so "oi" fires inside "depois" and a menu label containing a greeting keyword
  restarts the menu; and an agent's reply to `message.received` goes out beside the AI's, not instead
  of it. Flows run inside `MessagePipeline` and skip the AI. Their keywords match whole words,
  ignoring case, accents and punctuation, and a tap routes by its payload (`flow:<flow>:<step>`),
  never by its title.
- **Quick replies, not button templates.** A quick-reply tap arrives as an ordinary `messages`
  webhook (the title as text, plus `quick_reply.payload`), which the app already subscribes to. A
  button template's tap is a postback, which would need the `messaging_postbacks` webhook field and
  its own parsing. The cost: quick replies vanish once tapped and Instagram's desktop site doesn't
  show them, so a desktop customer sees only the text and their typed answer waits for the team.
- **Instagram conversation starters aren't read.** Their taps are postbacks too, so a company that
  kept ManyChat's starters on its profile gets those taps dropped. Typing the same question still
  works when the question is a keyword.
- **Instagram only** for now: `ChatFlow.problems()` refuses other channels until the WhatsApp client
  sends interactive buttons (3 reply buttons of 20 characters would fit the same model).
- **Team-only channels** (`teamOnlyChannels`) turn the AI off on a channel, as Rodrigo chose for that
  company: whatever no flow answers waits in the inbox. There is no other tenant-wide AI switch.
- **A person's reply stops the flow too**, because the paused-conversation check runs first: once the
  team answers in the inbox, that customer gets no menu until someone presses Resume.
- **Stored** as one `chat_flows` document per company (unique `tenantId`), read on every message, so
  a saved change applies at once; a flow with problems is skipped and logged (`Skipping chat flow`).
  The first company's flows were written with `mongosh` after a backup, before the editor existed. A
  keyword starts a flow at its first step, and each button names the step it leads to:

  ```js
  { tenantId: ObjectId("…"), teamOnlyChannels: ["INSTAGRAM"], flows: [{
      id: "servicos", name: "Menu de serviços", enabled: true, channels: ["INSTAGRAM"],
      keywords: ["Olá", "Bom dia", "Quanto custa a limpeza de pele?"],
      steps: [
        { id: "menu", text: "Qual dos serviços…?", buttons: [{ title: "Unhas", next: "contacto" }] },
        { id: "contacto", text: "Fale connosco: https://…", buttons: [] },
      ] }] }
  ```

  Ids are lowercase letters, digits, `-` and `_`. Instagram's limits apply: 13 buttons per step,
  titles of 20 characters, texts of 1,000 bytes.

### Edited in the Persona page's Menus tab (PR #75, 2026-10-13)

Asked for the same day ("add it as a tab on the Persona page"), so a company edits its own menus.
`docs/architecture.md` → "Chat flows" has the endpoints and `design-system/patterns.md` → "Persona" the
screen. Decisions:

- **Built from existing parts:** each message is an Agents builder card (`.flow--edit`) and its buttons a
  `.lines--replies` box, the line-items editor's newest variant. "New message…" in a button's Leads to
  adds a message, links the button and moves the cursor there, so a whole menu is built by typing.
- **The editor reads every flow, the pipeline only the ones that run** (`ChatFlowRepository.load` and
  `find`). With `find` behind the tab, a hand-written flow with a problem would have been hidden and
  then deleted by the next save; now it shows, to be fixed.
- **One set of rules, checked on both sides:** `ChatFlows.saveProblem()` (the tab's limits, then
  `ChatFlow.problems()`) and the same checks, order and codes in `app/menus.js` (`firstProblem`;
  `app.js` until PR #76). Either way the toast names the menu and message and the cursor goes to
  the field. A refusal is `menu_problem` with positions, so the persona's `{error, field}` errors
  stayed as they were.
- **The test chat plays the menus,** through the server's own `replyTo`, with unsaved menus and the
  tapped button's payload: the tab sits beside the test chat, and an AI answer to "Olá" there would
  contradict what Instagram customers get. Buttons show under the latest reply only, as on Instagram.
  With the AI off on Instagram, an AI reply carries a note saying Instagram would leave it for the team.
- **Like the rest of the persona,** admins and operators save and members read. A save replaces the
  whole document and the last one wins: there are no versions, unlike the persona's instructions.
- **Ids are kept:** new menus and messages get `menu-xxxxxx` and `msg-xxxxxx`, and existing ids never
  change, because quick replies already sent carry them in their payloads.
- Checked in Chrome with `scripts/persona-e2e/walkthrough.mjs` (now with a Menus section: build, the
  empty-message catch, the unsaved menu in the test chat, save, team-only, reload, dark, a 375px phone,
  a member), plus pt-PT by hand.

### The Menus tab as a menu builder (PR #76, 2026-10-14)

Asked to make the tab much better to use, with more features where useful. With three modest menus
the #75 tab was a 5,400px form: every menu open, keywords in a textarea, no view of where buttons
lead, Save at the very bottom. `design-system/patterns.md` → "Persona" has the screen. Decisions:

- **Its own script:** `app/menus.js` (`MenusUI`), mounted by `app.js` like Agents and Email. The test
  chat stays with the rest of Persona (`app/persona.js`, part of `app.js` until PR #84) and asks
  `MenusUI.dirty()` and `draftPayload()` for the unsaved menus.
- **Menus open in place:** one menu opens on load, several start folded, so the list is the
  overview: position, On/Off, keywords, size, 30-day usage, and a live "n to fix" (blocks saving)
  or "n to check" (a keyword clash, an unreachable message). Open state is kept by menu id.
- **Order is priority, so clashes are shown:** `replyTo` takes the first live menu with a matching
  keyword, so a keyword that holds an earlier live menu's keyword as whole words never reaches its
  own menu ("Hello there" behind "Hello"). The tab flags it with the server's normalization and
  ↑ ↓ reorder menus. The same matcher drives "Check a message" and picks Try it's keyword, and a
  duplicate starts Off so it can't clash.
- **Keywords are tokens, not lines:** Enter adds one, a pasted list adds one per line, leaving the
  field adds what was typed. Commas don't split: splitting "Quanto custa, por favor" would add
  "por favor", which matches nearly every message.
- **The map** walks from message 1 in button order; a message reached again is a link back, and
  messages no path reaches are listed and flagged on their card.
- **Usage reads the messages menus already store:** the origin now carries the step
  (`flow:<menu>:<message>`, the payload's form), and `ChatFlowUsage` counts 30 days on the existing
  `tenantId + createdAt` index, so no new index. Older messages count towards their menu only. A
  send Instagram refused (status `FAILED`) is counted apart, not as sent, or an expired token would
  read as healthy figures. Locally every send is refused (fake tokens), which is what the
  walkthrough checks.
- **Saving:** problems show under their fields only after a refused save (the head's count is
  always live). The save bar follows the screen only while something is unsaved, and toasts rise
  above it: a toast at the bottom centre covered its Save button and took the click. Discard
  confirms (it's always within reach now), Ctrl+S saves, leaving the page asks first, and edits
  typed while a save is in flight stay unsaved instead of being dropped.
- **Redraws never touch a field being typed in,** and a region is replaced only when its HTML
  changed, so a button under the pointer isn't swapped out mid-click (a keyword committed on blur
  re-rendered the tokens before the click on one's × landed).
- Checked with `scripts/persona-e2e/walkthrough.mjs`, whose Menus section now also covers the
  tokens, the map, problems under fields, Try it, a clash, the checker, reordering, duplicating,
  discarding and a signed Instagram message showing up in usage; plus dark, a 375px phone, pt-PT
  and a member.

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
