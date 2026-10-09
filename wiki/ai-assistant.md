---
updated: 2026-10-09
---

# Dashboard AI Assistant

The `ai-assistant` module: the team's copilot in `/app`, which proposes changes on cards a person confirms. It is not the customer-facing persona ([ai-persona-and-bot.md](ai-persona-and-bot.md)).

## AI Assistant: its own prompt, settings and checked cards (PR #55, shipped 2026-10-08, `b570df5`)

A Cursor cloud agent's PR (branch `cursor/ai-assistant-e2e-79bf`, opened 2026-10-08). The
`ai-assistant` module is the team's copilot (conversations with confirm-before-execute cards), not the
customer-facing persona. Per the PR, checked in the code where it names files:

- **Its own prompt (`AssistantPrompt`).** It used to reuse the bot's CRM prompt, which tells the model
  to summarise a change and wait for "sim", so every change was confirmed twice: in text, then on the
  card. Changes are now confirmed on cards only. History goes back to the model as real tool calls plus
  their outcome (waiting, result, error, cancelled, expired); failed turns are left out and long
  results shortened.
- **Cards:** a new message expires the cards still waiting in that conversation. Before a card is
  shown the server runs `ToolPack.check`. If the change can't run (an unknown service, a phone another
  client has, a closed 24-hour WhatsApp window), the reason goes back to the model instead of a card
  that would fail on Confirm. Action ids are minted by the server (`act_<ObjectId>`), because provider
  ids (`call_1`…) repeat across turns and hit the unique index.
- **Failures are turns:** model down, budget spent and empty replies are stored as error turns with
  Try again (`POST /app/api/assistant/threads/{id}/retry`). The monthly token budget is checked before
  the model is called (it didn't apply to the assistant before), and usage counts as `assistant`.
- **Settings per company** (`dashboard_assistant_settings`, `_id` = tenant id, defaults when absent):
  instructions up to 4,000 characters (added last, in a block they can't close), reply style (concise,
  balanced, detailed), a fixed reply language or each message's, an "answers only" switch that removes
  the write tools and refuses confirms with `403 changes_off`, and areas kept out. Areas are
  `AssistantAreas`, the modules it has tools for; a newer module such as `timesheets` isn't one.
  Admins edit, members read.
- **Tools** (`AssistantTools.kt`, plus `describe`/`check` on the CRM and booking packs): a business
  overview, a client's whole record with the company's own fields, client create and update following
  the company's required fields, invoices and quotes with filters and an effective "overdue" (a quote
  becomes its invoice through `QuoteInvoicing`, shared with the dashboard), services, bills to pay,
  suppliers, employees, and customer chats. Customer text reaches the model marked untrusted, and
  replies go out through the inbox in the confirming person's name.
- **Home** counts only the viewer's own waiting changes (`OverviewService.build(assistantOwnerKey)`);
  it counted everyone's.
- **Page** moved out of `app.js` into `app/assistant.js` (`window.AssistantUI`, mounted from `app.js`
  like Agents): markdown escaped first (bold, italics, code, links, lists, headings, tables, which the
  2026-09-25 renderer lacked), cards with "Open client/quote/invoice" and the PDF, rename, search and
  delete, starter questions per module, and a settings drawer.
- **Mobile:** the backend sends a card's `preview` as an object and the app expected a string; it now
  reads JSON. Settings, rename/delete and retry are web-only.
- **Not covered:** answer quality with the production model. The walkthrough uses the stand-in model,
  so a manual pass is still worth doing, especially the Portuguese wording.

Merging it: one conflict with #54, in `app.js`, where #55 deleted the old assistant functions and #54
had rewritten the persona code right after them. The old block went and #54's code stayed (nothing in
#54 called the removed functions). `.gitignore` had #55's generic `/scripts/*/` walkthrough rules and
#54's persona-only copies; the copies went. CI deployed `b570df5`, health ok, 0 `ERROR` lines.

## Gaps found in a dashboard "Assistente IA" pass (2026-09-25, fixed same day — commit `10e5ee5`)

PR #55 (above) later gave the assistant its own prompt (`AssistantPrompt`) and its own markdown renderer, so the prompt and rendering fixes below are history. The `hashchange` and disabled-field fixes still apply across `/app`.

Tested as a real production tenant (zero channels connected — WhatsApp/Instagram/Site all
"Não ligado" — so the internal dashboard Assistant, not the WhatsApp bot, was the only AI surface
reachable). All findings below were fixed and deployed same-day, then re-verified live against
production:

- **The dashboard assistant runs on a different, much weaker system prompt than the WhatsApp bot.**
  `DashboardAssistantService.ASSISTANT_PROMPT`
  (`src/main/kotlin/com/rfm/edubot/dashboard/DashboardAssistant.kt`) is a short, independent prompt
  that never calls `SystemPrompts.crmPromptFor(...)` — none of `CRM_RULES`' discipline (search
  before create, one-shot confirmation, id-freshness, quote-vs-invoice distinction, currency
  formatting) applies here. `MessagePipeline` (WhatsApp) is the only caller of `crmPromptFor`.
  Reproduced: asked the dashboard assistant to create a quote for "Hotel Miradouro", an existing
  client with service history — instead of calling `search_clients` first (which works fine when
  asked directly), it proposed **create_client** for a duplicate "Hotel Miradouro". Cancelled
  before confirming to avoid polluting the tenant's real client list. This is the same class of bug
  the 2026-09-11 pass fixed for WhatsApp, just never ported to the dashboard assistant.
  **Fixed:** `DashboardAssistantService.completeTurn` now composes `SystemPrompts.crmPromptFor(...)`
  the same way `MessagePipeline` does. Re-verified live: the same repro now resolves the real
  client id via `search_clients` instead of proposing a duplicate.
- **Neither prompt gives the model the current date/time or tenant timezone.** Grepped both
  `MessagePipeline` and `DashboardAssistant` — no "today's date is X" (or equivalent) is ever
  injected into context. Reproduced: asked "What bookings do we have today?" (2026-09-25) and got
  "Hoje não há reservas registradas" (no bookings today) — false; the Bookings calendar and the
  Início overview card both show a booking that day (16:30, Bruno Esteves). Any relative-date
  question ("today"/"this week"/"tomorrow") is unreliable for both the dashboard assistant and the
  WhatsApp bot for this reason.
  **Fixed:** added `SystemPrompts.currentDateTimeContext(timezoneId)` (tenant's real
  `Tenant.timezone`, default `Europe/Lisbon`), injected into both pipelines' context. Re-verified
  live: the same repro now lists the real bookings for today instead of claiming there are none.
- **Assistant replies are markdown but rendered as plain escaped text.** Both
  `renderAssistant`/`renderThread` in `app/app.js` (lines ~2261, ~2465) do
  `escapeHTML(m.content)` with no markdown parsing, while the model (unprompted to avoid it, in the
  dashboard assistant's case) writes `**bold**` and numbered lists. Users see literal asterisks.
  Same pattern likely affects the persona/customer chat preview in Settings, which reuses the same
  rendering function.
  **Fixed:** added `renderChatText` (escape, then `**bold**` → `<strong>`) in `app/app.js`, used by
  both `renderAssistant` and `renderPersonaChatLog`. Re-verified live: bold renders correctly.
- **Language matching is unreliable.** Asked the dashboard assistant a question in English mid
  Portuguese-conversation; it replied in Portuguese despite both `SystemPrompts.V1` and
  `DashboardAssistant.ASSISTANT_PROMPT` instructing it to match the user's language.
  **Fixed:** strengthened the instruction in `V1`, `CRM_RULES`, and the dashboard
  `ASSISTANT_PROMPT` to explicitly follow the most recent message's language, not the
  conversation's earlier language. Re-verified live (English mid-conversation answered in English).
- **Composer starts disabled with no visual "disabled" affordance.** `app/app.js`'s
  `renderAssistant` disables `#assistant-input`/the send button whenever there's no `current`
  thread (i.e. before "Nova conversa" is clicked or a thread selected) — correct by design, since
  messages need a thread — but the textarea isn't styled any differently while disabled, so typing
  silently does nothing and it isn't obvious a click on "Nova conversa" is required first.
  **Fixed:** added `.inp:disabled`/`.sel:disabled`/`.txt:disabled` styling sitewide in
  `admin/style.css` (greyed background, `not-allowed` cursor) — a design-system-level fix, not an
  assistant-only patch.
- **Separate, non-assistant-specific bug that affects every dashboard tab including
  Assistente IA:** the SPA has no `hashchange` listener (`grep hashchange` returns nothing in
  `app/app.js`); only nav-link click handlers call `location.hash = tab` and re-render directly.
  A same-document hash navigation that isn't a nav click — browser Back/Forward, or an
  automation/`navigate()` call to `#some-tab` while the app is already loaded — updates the URL bar
  but **not** the visible view. Reproduced repeatedly and consistently (Back button, and direct
  hash navigation to `#ai-assistant`/`#settings`/`#catalog`/`#overview` all failed to update the
  view when the SPA was already loaded; a genuine fresh page load with a hash always worked fine).
  Likely explains some of the "features don't work" impression if the user navigates with browser
  Back/Forward inside `/app`.
  **Fixed:** added a `hashchange` listener in `init()` (`app/app.js`) that calls `setActive(tab)`
  when the hash changed without going through a nav click, guarded against the loop `setActive`'s
  own `location.hash = tab` would otherwise cause.

**New gap surfaced during live verification of the fixes above, not yet fixed:** asked the
(now crmPromptFor-backed) dashboard assistant to create an **orçamento** for an existing client,
phrasing the due date as "vencimento em 30 dias" — it proposed **create_invoice** instead of
**create_quote**, violating `CRM_RULES`' explicit "never create a fatura when the user asked for
an orçamento" rule. Cancelled before confirming; no bad data written. Caveat: "vencimento" is the
invoice-associated term in `CRM_RULES` (quotes use "validade"/`valid_until`), so this test
phrasing may have nudged the mix-up rather than this being a clean instruction-following failure —
retest with "validade" before treating as confirmed, and if it reproduces cleanly, the fix is
likely restating `CRM_RULES`' quote-vs-invoice rule more prominently or nearer the tool-call step.
