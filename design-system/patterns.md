# Patterns

Page-level recipes. Markup classes are defined in [components.md](components.md).

## Dashboard shell

Every `/app` and `/backoffice` page uses this structure:

```
body
  aside.sidebar
    .brand
    nav.nav
    .sidebar__foot
  main.main
    header.topbar
    section.view#view
  .drawer#drawer[hidden]
  .toast#toast[hidden]
  (.confirm#confirm[hidden] — when the surface deletes/overwrites)
```

`body` is a two-column grid (`--sidebar` | 1fr). Do not wrap this in a React-style app frame. Do not add a second top nav.

Head assets (order matters):

1. Google Fonts (Outfit, Nunito, JetBrains Mono) + preconnect
2. `/admin/style.css`
3. `/admin/theme.js` (synchronous, in `<head>`)
4. `/admin/catalog.en.js`, `catalog.pt.js`, `catalog.es.js`
5. `/admin/i18n.js`
6. Page script `defer` at end of `<body>`

`/app` declares `<html data-layout="minimal">`, its fixed skin ([tokens.md](tokens.md#layout-skins-htmldata-layout)); there is no layout switch. `/backoffice` declares nothing and stays classic. Every `/app` screen must read well in the minimal skin in both themes. The sidebar foot ends with the "Powered by The Bots Lab" credit ([components.md](components.md#sidebar-kpis)).

## List module (default screen)

Used by clients, quotes, invoices, catalog, tenants:

1. `.view__hero` with title, description, optional `.view__stats`
2. `.panel` with `.panel__head` (title + optional `.chip` filters and compact `.sel` in `.panel__tools`)
3. `.tbl-wrap` > `table.tbl`
4. Empty: one row, `colspan`, inner `.empty`
5. Row actions in `.actions` (`.btn--sm`, `.iconbtn`)
6. Create/edit in the **drawer**, not a full-page form
7. `#btn-new` in the topbar shows only when the active module can create

## Drawer form

1. `openDrawer({ eyebrow, title, body, wide, onSave, saveLabel })` (or equivalent)
2. Body is a `.form` / `.form__grid`
3. Footer: ghost Cancel + accent Save
4. On save: disable button, swap label to saving, close only on success
5. Focus first input after open. A drawer that leads with status and keeps its fields further down (the backoffice agents drawer) passes `autofocus: false` and focuses its close button instead, so it opens at the top
6. Close via `[data-close]`, scrim, and Escape; trap Tab inside the panel; restore focus on close
7. Wide (`.drawer__panel--wide`) for line-item editors
8. Quote / invoice rows open the drawer for status, convert, or edit; a client row opens the client record — not a new page

## Login

Render `.auth` > `.auth__card` into `#view`. Keep brand mark letters consistent with the surface (CRM / AI / BO). POST existing auth endpoints; do not add a new login visual.

Google sign-in (backoffice and `/app`) goes above the password form when the page's auth config returns `google` ([components.md](components.md#auth-card)). Load the Firebase SDK as soon as the login shows, and call `signInWithPopup` before any other `await` in the click handler: browsers only allow the popup straight from the click. The Firebase session stays in memory (`inMemoryPersistence`); the page keeps its own token.

## Confirm then destroy

Never `window.confirm`. Fill `.confirm__title` / `.confirm__body`, show `#confirm`, ghost cancel + `.btn--danger` confirm.

## Toast feedback

Success and recoverable errors: `toast(translatedString)`. Do not use `alert()`. One node, ~2.8s.

## Filters

Chip group in `.panel__tools`. Selected chip gets `.is-on`. Filtering is client-side unless the module already hits an API query param. When the filter is a long entity list (clients on Serviços, suppliers on Pagamentos), use a compact `.sel` beside the chips instead of one chip per row.

Archived or deleted records (the `/app` directories' Archived chip, the backoffice's Deleted tenants) stay out of the default list, its counts and "All". Their own chip swaps them in with Restore as the only row action and the date they left in place of last activity. Status chips carry a `.chip__count`. When a search finds nothing under the current chip but matches under another, the empty state says so and offers a `Show N in …` button to that chip.

## Home (tenant snapshot)

`/app` overview is a manager snapshot of **enabled modules**, not a KPI wall of raw counts. It should read as graphical — big tinted numbers and module color, not a stack of label/value form rows:

1. `.view__hero` with 3–4 processed highlights as `.stat--lg` spotlight tiles (received this month, outstanding, open quotes, waiting chats — only for modules the tenant has on). A highlight's hint gets `.delta--up` / `.delta--down` from its `deltaPct` sign, not plain muted text.
2. `.pulse` health strip (`pulse--ok` / `--watch` / `--urgent`) with a `.pulse__icon` and a one-line summary
3. `.queue` of items that need a person (overdue invoices, overdue payments, waiting chats, pending bookings, unreplied Instagram, expiring quotes), each row led by a `.queue__icon`. The "Needs you" heading carries a `.tag` count.
4. `.home-grid` of `.snapshot` panels — one per enabled operational module (financeiro, pipeline, clients, services, suppliers, employees, inbox, calendar, Instagram, catalog, assistant). Each card gets a module `data-kind` (drives its `--snap` accent + `.snapshot__icon`), a single large `.snapshot__figure` headline number, an optional `.meter` when the number is a ratio, and secondary numbers in a `.snapshot__metrics` grid — not a vertical label/value list. Tenants pick which of these appear under Settings → Home (`GET`/`PUT /app/api/settings/overview`). Hidden cards stay off until turned back on; new modules still show by default. The **Financeiro** card is the one exception that reads from two modules at once (invoices' `o.cash` + payments' `o.payments`): it shows if either is enabled, and folds both into one simple received-vs-spent card ([components.md](components.md#snapshot-grid)) instead of a separate card per module — money in and money out belong together, not split by which internal collection they come from.
5. `.setup-list` only when setup is actually unfinished **and** relevant (CRM-only tenants are not asked to connect WhatsApp)

Home has a **Choose cards** control in `.home-title-row` next to the page title (not in the highlight stats). It opens Settings → Home. Snapshot clicks set `data-go` (and optional `data-conversation` / `data-settings`) then switch module. Data comes from `GET /app/api/overview`; do not fan out to every module list to render Home.

## Home (minimal layout)

`/app` always renders this Home: `renderOverview` hands off to `renderOverviewMinimal` because the page pins `html[data-layout="minimal"]` (the classic Home above is no longer reachable and is kept only until that code is removed). A CRM cockpit, clean but dense. Settings → Home picks its cards (same hidden-card ids). Data comes from one call, `GET /app/api/overview?extended=1`, which adds `cashFlow`, `activity`, `agenda`, `recent` and `topClients`. Empty lists are omitted from the JSON, so treat a missing array as empty.

1. `.dash__head`: tenant-local date, time-of-day greeting, health line (`pulse`), then **Choose cards** and up to three quick-create buttons (first one primary). Quick create switches to the module, then opens its existing drawer form.
2. `.dash-kpis` (`highlights`): up to five processed numbers — received (with vs-last-month delta and a 6-month spark), to collect (overdue in red), net (received − spent, when payments exist), open pipeline, waiting chats (14-day spark), then bookings today / clients. Each respects its module card being hidden.
3. Setup row (`setup`) as a `.panel--card` of `.btn--sm` actions, only when unfinished.
4. `.dash-grid`, priority order via `data-order`: 1 Needs you (`attention`, main) · 2 Today (`calendar`, side) · 3 Cash flow (`financeiro`, main) · 4 Receivables & payables (`financeiro`, side) · 5 Pipeline (`pipeline`) · 6 Inbox (`inbox`) · 7 Top clients (`customers`, needs invoices) · 8 Recent activity (visible while one of its source cards is) · 9 Agents (`agents`, side: to approve / open tasks / runs today as `.dash-figures`, then active agents, tasks due and failed this week; a `.notice--warn` when agents are paused). After render, `balanceDashColumns` may move one of cards 4–9 to even out column heights; 1–3 never move.
5. `.dash-tiles` for services, suppliers, employees, catalog, Instagram, assistant (and clients when not already a KPI).

Rows deep-link: `data-go` switches module and `data-open` opens that invoice / quote / payment / client / booking. Agent rows (an approval waiting, a task due by the end of today, a run that failed) carry a ref — `approval:ID`, `task:ID` or `run:ID` — and open that approval, task or run over the Agents inbox or activity tab (`AgentsUI.focusRef` picks the tab, `AgentsUI.openRef` the drawer). A task from an earlier day reads "Overdue task" with the `late` tone. A connected Gmail account that Google stopped honouring (`integration_reconnect`, `bad` tone, the account address as detail) carries `data-settings="channels"` and opens Settings → Channels, where an admin reconnects it; the same notice reaches admins in the bell. A single overdue invoice or payment keeps its own row; two or more collapse into one count row. Money aggregates use whole euros; per-document amounts keep cents. Relative times and dates come from `Intl.RelativeTimeFormat`, never catalog strings.

## Services (client work)

`/app` Services is a CRM table of work attached to a **client**, not the service catalog. Recipe: `.view__hero` + stats (open / invoiced) + `.crm` panel with a `.panel__views` **List / By week / By month** switch (see [components.md](components.md#panel-with-a-view-switch-list--by-week--by-month)) and a `.panel__filters` row underneath holding a compact `.sel` client filter, status chips, and an Invoice selected action. The view switch and the filters are deliberately separate rows — switching period reshapes the whole panel (hero, stat row, table columns), so it must not look like just another chip next to status/client. By week and By month replace the hero with a **selected** period (Monday week or calendar month, current by default): jobs, open €, invoiced €, and total, with a vs-previous hint on the total. A `.period-nav` (see [components.md](components.md#hero-with-a-period-navigator)) sits above the hero stats with ‹ / › to step to any period that has a row below and a "Current" jump back — it isn't locked to today's period. The table is that history — a `.is-total` row, then one row per period (jobs and euros in separate columns). The row matching the nav's selected period uses `.is-current`. Cancelled rows stay out of the money. List view hero stays all-time. Stats and rows follow the chosen client. Rows use a leading checkbox (`.tbl td.check`) so several open rows for the same client can become one invoice. A row opens the service **detail** (same shape as an invoice detail): the client name links to the client record; the meta shows when it was done, quantity × unit price, the booking it came from and the invoice that billed it; actions follow the status (open: Invoice, which lists the client's open work with this row ticked, Edit, Cancel service, Delete; invoiced: Open the invoice, locked; cancelled: Reopen unless a booking drives it, Delete), plus Open booking. Edit and New open the form (New prefills the filtered client and today's date, and shows the total as you type). Prefill from the catalog is optional (bookable services are catalog services, so there is one list); the client is required. Rows created by completing a booking carry a muted "from booking" suffix.

## Bookings

`/app` Bookings books the tenant's **catalog services** — there is no separate booking-services list. A catalog service is bookable once it has the Bookable flag and a duration (Catalog form, or the Services drawer on this page, which edits the same catalog rows).

1. `.view__hero` with four stats: today, the shown week, to confirm, and the shown week's value (sum of booking prices, cancelled and no-shows excluded).
2. Setup queue (`.overview-block` + `.setup-list`, numbered `.queue__icon`) only while no service is bookable or no opening hours exist.
3. `.booking-toolbar`: Week / Agenda switch, a `.period-nav`-style week stepper (Week view only), and the Services / Opening hours drawers.
4. Week view: a "To confirm" `crmPanel` (pending upcoming bookings, one-click Confirm / Decline) above the `.cal-grid`. The grid's hours stretch to the opening hours and to any booking outside them; closed hours are hatched but still clickable for staff.
5. Agenda view: next 60 days as one table grouped by `tr.is-day`, with status chips (All / Pending / Confirmed / Completed / No-show / Cancelled) and the topbar search.
6. A booking opens a **detail drawer** first (`.detail__head` + `.detail__meta` + `.detail__foot`): Confirm, Mark done, No-show, Cancel (through `.confirm`), Reopen, Invoice (once done and billed), Edit, and Open service once it produced a Serviços row. The contact name links to the client record. Edit shows the form: service, date + time with `.slot-picks` free times, duration, price, contact with client `.suggest`, status, notes.
7. Marking a booking done adds an open Serviços row for its client (price = the booking's price); Reopen cancels that row if it isn't invoiced yet. Customer bookings (WhatsApp, Instagram, website chat) must fit the opening hours and be in the future; staff can override both.
8. Every booking gets a CRM client when the Clients module is on: the contact is matched by phone (formatting ignored) or created. The client record shows the next booking, visits and no-shows, flags past bookings nobody closed, and books for that client.

## Clients (directory + record)

`/app` Clients is the directory recipe (hero + stats + table), sorted by name; the search also matches email, NIF and phone digits typed without spaces. The list holds the whole directory (the API used to stop at 20). A row opens the client **record** in the drawer, not an edit form ([components.md](components.md#record)):

1. Profile card: initials, contact lines (email opens mail, address opens Maps), NIF, "client since · visits · no-shows · last activity", Edit. Then Call, WhatsApp (`wa.me`; a number typed without a country code is Portuguese) and Open chat when a WhatsApp conversation matches the phone (last 9 digits, like bookings), and the staff notes.
2. Money strip, up to four cells, each only with its module on, in this order: outstanding (a pending invoice past its due date counts as overdue, as on Home), next booking, to invoice (open Serviços), billed. In proposal (open quotes) only fills a free cell.
3. Needs attention, only when something does: overdue invoices, past bookings never marked done or no-show (so never billed), bookings to confirm, open work to invoice (one click to an "Invoice open work" form with the rows ticked), quotes awaiting a reply or not sent yet, invoices due within 7 days.
4. Chip tabs: Activity (upcoming bookings, then the history of bookings, services, quotes, invoices, payments received and the WhatsApp chat) · Bookings · Services · Quotes · Invoices · Emails (while the company has a Gmail account: what went to or came from the client, see [Email (Google)](#email-google)) · Automations (with Agents on: runs in progress, open tasks and recent runs on the client and its documents, each saying what the agent did; "run an agent" for agents run by hand on clients; Pause automations). Every row opens its own drawer.
5. A sticky create bar: New booking, Add service, New quote, New invoice, each prefilled with the client.

Drawers opened from the record show `← client` and return to it (same tab, fresh data) once they save; ×, scrim and Escape leave it. Edit holds name, NIF, phone, email, address and notes ("only your team sees these"). The phone field warns as soon as another client has the number, whatever the formatting, and links to them. Phone is unique per tenant for clients, suppliers and employees, so saving the exact same number is refused with a clear message (`409 phone_taken`) instead of a generic failure; the warning is what catches the same number typed differently. A new client opens its record after saving. Staff notes never reach the bot's CRM tools. The NIF prints on quotes and invoices on the client-number line.

Pausing a client's automations is any member's brake for a client who asked not to be contacted: agents neither start nor carry on anything on the client or its documents until someone resumes (runs already waiting end as cancelled). The card says so, and the Automations tab shows a `.notice--warn` with Resume in place of the pause button and the run row.

## Invoices

`/app` Invoices uses the same `.panel__views` switch over a `.panel__filters` row of status chips, and the same `.period-nav` above the hero stats. **By week** and **By month** replace the hero with the selected period: paid, pending, overdue, and total, with a vs-previous hint on the total. The table is the history (`.is-total`, then one row per issued period). The row matching the nav's selected period uses `.is-current`. Cancelled rows stay out of the money columns. The list view hero stays all-time.

## Financeiro (combined money ledger)

`/app` Financeiro is a read-only lens over two other modules — paid invoices (money in) and paid payments (money out) — not a module of its own; it shows in nav whenever Invoices or Payments does (no separate enable toggle), and there's no "New" action. Same recipe as Services/Invoices: `.view__hero` + stats + `.period-nav` + `.crm` panel with `.panel__views` **List / By week / By month**. Unlike Services/Invoices, the `.panel__filters` row (a type chip: All / Recebido / Gasto) only appears in List view — By week/By month always shows both directions together, so a type filter has nothing to narrow there. List is a flat chronological ledger, newest first, merging paid invoices and paid payments by `paidAt` (not `dueDate`/`createdAt` — a transaction belongs to the period it actually landed in). By week/By month rolls that same data into one row per period: received, spent, net, with `.is-total` and `.is-current` exactly like Services/Invoices. The hero's three stats are received / spent / net — List view shows all-time totals (matching how Invoices' list-view stats are all-time), period view shows the selected period with a vs-previous hint on net. Clicking a ledger row or the Overview Financeiro card's Open button both land here now, not on Invoices/Payments directly — Financeiro is the entry point for "how are we doing financially," Invoices/Payments stay the place to act on a specific pending or overdue one.

## Suppliers (vendor directory)

`/app` Suppliers is the clients list, inverted: people and companies the tenant **pays**. Same directory recipe (hero + stats + table). A row opens the supplier **record** ([components.md](components.md#record)): profile card (phone, address, "supplier since · payments · last paid", Edit, Call, WhatsApp), money strip (to pay with the overdue part, next due, paid), needs attention (overdue payments, payments due within 7 days), the payments table (a row opens the payment), and a sticky Add payment prefilled with the vendor. Employees get the same record with their role instead of an address. A new supplier or employee opens its record after saving.

## Payments (outgoing bills)

`/app` Payments is invoices, inverted: bills attached to a **supplier** or, when that module is on, an **employee**. The form uses `.chip` to pick the payee kind, then a `.sel`. A supplier payment uses the catalog line editor. An employee payment is one amount and an optional description (default “Payment”), not a catalog line. Opening a payment uses the same detail drawer as an invoice: `.detail__head`, `.detail__meta`, the lines table, `.detail__foot` with Mark paid.

Quote, invoice, payment and booking details share these rules: the name in the head links to the client's or payee's record; the meta shows when the document was created and, while unpaid or open, how far away its date is ("due in 3 days", "was due 12 days ago", "expires in 6 days"); a pending document past its due date shows as overdue, as on Home. Documents link to each other (a converted quote shows and opens its invoice and no longer offers Convert, and the server refuses a second conversion with `already_invoiced`; an invoice opens its quote). The quote detail has the PDF button like the invoice. Every link goes through the drawer trail, so Back and saving return to where the user came from. Recipe: `.view__hero` + paid / to-pay / overdue stats + `.crm` panel with a compact `.sel` payee filter in `.panel__tools` plus status chips. Mark paid in the row or drawer. No PDF in v1. Creating a payment with neither suppliers nor employees opens the supplier form first.

## Conversation / assistant

Two-column `.assistant` on desktop; stacks at `760px`. Transcript uses `.chat__*`. Tool-call confirmation uses `.assistant__action` (accent border, confirm + cancel). Do not auto-execute. After a confirmed `create_invoice` / `create_quote`, reuse `.pdf` in `.assistant__action-buttons` so the user can download the generated document.

With the agents module the assistant can also list agents and their approvals, run an agent on a record, pause or activate one, approve or reject an item and draft a new agent. These writes use the same card. Its title names the agent and the record from the action's `preview`, which the server fills in when it proposes the action, so the card never shows ids. Its details list what an approval would send, or the request for a draft. Members aren't offered pause, activate or draft. After a confirmed draft or run, a button in `.assistant__action-buttons` opens the draft in the builder or the run in Activity.

`/app` Conversations is this same split inbox (thread list + live reply), not a table that opens a drawer.

## Instagram (comments inbox)

Optional `instagram` module. Work queue first, not an Insights wall:

1. `.view__hero` + unreplied / posts stats
2. Chip filter: needs a reply vs posts (`.panel__tools` chips)
3. Comment/post tables with `.ig-thumb` in the first column; row click opens the **drawer**
4. Drawer lists `.ig-comment` items and a reply form; do not auto-send
5. Empty / not-connected / reconnect copy goes through i18n. Reconnect is Settings → Channels.

## Agents (automations)

Optional `agents` module, grouped with Persona and the AI assistant. `app/agents.js` is mounted by `app.js` (`AgentsUI.init(deps)`), which passes its drawer, table and formatting helpers; the page reuses them instead of copying them. Chip tabs (`.settings-tabs`): Agents · Templates · Inbox · Activity · Settings.

1. `.view__hero` with active agents, runs today, waiting for you, failed this week; a `.notice--warn` below it while agents are paused (by the company, with Resume, or by the platform, without).
2. **Agents**: `crmPanel` table (`.agent-cell` + `.recipe`, status pill, last run, runs) with Active / Drafts / Paused chips. A row opens the agent record drawer: `.record-card`, `.notice` for problems, KPIs, Overview (`ol.flow` + stop rules + rules) and Runs chips, and Test · Run now · Activate/Pause in the foot. Empty state: templates first, then "Describe it" and "Start from scratch".
3. **Templates**: `.gallery` with category chips. A card opens a guided setup drawer (schema-driven questions) that creates a **draft**; activating is always a separate step. The gallery starts with two `--blank` cards, "Describe it" and "Start from scratch". "Describe it" (admins) opens a form drawer: a textarea for the request in the user's words, example chips that fill it in, and a hint that the result is a draft to review. While the model drafts it (`POST /app/api/agents/draft`, several seconds) the textarea is read-only and the submit button says so. The builder then opens on the draft with a `.notice--info` on top: what to check before activating, plus the model's note on what it left out. If the user closed the drawer meanwhile, a toast says the draft is ready instead of reopening it. Failures (AI unavailable, token budget spent, no usable answer) are toasts; the request stays in the textarea.
4. **Builder** (Edit): a wide drawer of `.builder__section`s drawn from the catalog's JSON schemas (`x-widget` picks the control). "Test changes" dry-runs the unsaved draft. Saving keeps a draft with problems; an active agent must stay valid. A WhatsApp message step's template picker (`wa-template`) lists the approved templates that can be sent (not media headers or one-time codes) as name · language, shows the one picked in a `.wa-preview` bubble and adds one field per variable, in the template's order, each with the variable tools. Variables are kept by position, so one left empty stays in its place. The template only goes to a known number outside the 24-hour window: inside it the step's text goes, and without a number the fallback gets the text. An approval warns when the template was deleted or is no longer approved (`warnings.template_not_found`) or a variable came out empty (`warnings.template_params`), since the step would fail.
   AI steps use the same card. "Ask AI to do a task" has its instructions (a template field), the fields to return (`.ai-outputs`, where a choice lists its options) and a "look up" check. Its actions are `.chip-picks`, none picked by default so it only reads. "Before acting" shows up once an action is picked. Later steps offer the fields as `{{steps.<id>.output.<name>}}` and in "Only if" rows, where a choice's options fill a select. "Write with AI" has a brief, a channel and a length, and the Voice section decides whether it follows the Persona.
5. **Inbox**: a `.worklist` of approvals (agent · action, record, excerpt, expiry pill) or tasks, with Approvals / Tasks / My tasks chips. The approval drawer shows the draft (editable fields as `.txt`), Approve and Reject (confirmed), and links to the record and the run.
6. **Activity**: a runs table with In progress / Needs attention / Finished chips and a "Show tests" check. The run drawer shows the trigger, an outcome `.notice` and each step's result on `ol.flow`, with Retry or Cancel. An AI task's step lists its fields in `dl.dash-facts`, then each action it took on its own line, then (when drafted) what it would have done, with the same preview an approval shows. An AI text shows in a `.wa-preview` bubble.
7. **Settings**: the company defaults form (read-only for non-admins) beside a usage `dl.dash-facts` of platform limits.

The backoffice manages a company's agents from the tenants table: with Agents on, the row actions get a ghost **Agents** that opens a drawer on that company. Strings live under `backoffice.agents.*`; status labels reuse `app.agents.status.*`.

1. A `.notice` on the pause state: plain while agents run, `--warn` while the platform or the company paused them. Its one action is Pause agents (`.btn--danger`, through the confirm) or, once the platform paused them, Resume agents (`.btn--accent`). A company's own pause is only described: its admins lift it in Settings, and pausing here as well keeps the agents paused.
2. A "Last 7 days" panel: `dl.dash-facts` of runs, succeeded, failed or waiting for review, waiting or in progress, and approvals waiting now.
3. A `.tbl` of the company's agents: name (with a muted pause reason under it), status pill, runs, failed, last run; `.empty` when there are none.
4. A Limits panel: active agents allowed, runs per day, emails per day, saved by the drawer's Save. A cleared field keeps its limit rather than reading as 0, which allows none. The company sees these read-only in Settings, and a platform pause as a notice without Resume.

The nav badge counts pending approvals plus open tasks. Record links (client, quote, invoice, payment, booking, service, chat) go through `app.js`'s `openAgentSubject` with a drawer-trail back link to the run or approval; there's no "Open record" when the trail already goes back to that record.

Agents also show up where the work is: Home's Agents card and Needs-you rows (see Home (minimal layout)); the client record's Automations tab; the same block under quote, invoice and booking details (only when an agent has run there, is waiting there, or can be run there by hand); the Persona page, which lists the agents with "Write with AI" steps and a "Follows it" check per row (admins only; hidden when there are none); and the top-bar notifications (see Notifications). After "Run now" there, the block looks again every 1.5 s (up to five times) until the run is no longer queued or running, so it ends on what happened.

## Settings (tenant)

Chip tabs (`.settings-tabs`): Home · Appearance · Channels · Website · Language · Documents. Home is a `.choice-list` of `.queue__item.choice` toggles (visible cards get `.is-on`). Appearance is the same list for Theme (light / dark), a browser preference (localStorage) applied through `UIPrefs.setTheme`, not a tenant setting. Channels lists WhatsApp, Instagram, Email (Google) and the website widget in one table. Website includes snippet, allowed origins, and a `.widget-preview`. Documents mounts the template studio. Do not dump every settings panel into one scroll.

## Email (Google)

A company emails from its own Gmail or Workspace account ([components.md](components.md#email-google)):

1. **Connect** (Settings → Channels): only a company admin signed in as themselves connects (an operator would consent with their own Google account; they can still change the settings, send a test and disconnect). The click opens the popup before any `await` (browsers only allow a popup straight from the click), then points it at the authorize URL; a blocked popup sends the whole page to Google instead. Google comes back to `/app/?google=connected` or `?google=error&reason=…`: in the popup, `handleOAuthPopup` posts the outcome to the opener and closes. Google's pages send `Cross-Origin-Opener-Policy`, which can leave the popup without `window.opener`, so a window marked in `localStorage` as the consent popup answers over the `google-oauth` `BroadcastChannel` instead (and boots the app itself if the browser won't close it). In the page, `takeGoogleRedirect` reads the outcome once, drops it from the address and opens Channels. Outcomes toast from `app.integrations.google.reasons.*`. Reconnect passes `?account=` so Google opens on that account.
2. **Account drawer**: sender name, reply-to and signature shape every email from the account; a test email goes to the person asking (an operator: the account itself); Disconnect confirms and deletes the tokens and the mail kept from the account.
3. **Send by email** on quote and invoice details opens a draft in the company's language (server copy), addressed to the client, with the PDF. The drawer picks one request id when it opens, so pressing Send twice sends once. Sending a pending quote marks it sent. Errors toast from `app.integrations.google.sendErrors.*` (daily limit, reconnect, invalid address…).
4. **Emails** on the client record lists that client's mail; there's no Emails tab while the company has no account (disconnecting deletes the kept mail).
5. **Inbox reading** (only while the platform allows it, `google.inbox` true): the account drawer's Inbox panel turns it on and off with `PATCH {inboxSync}`, no Save. An account that can only send asks Google first: the click opens the consent popup with `?inbox=1&account=…`, which adds the read scopes to what the account already granted, and Google comes back with `?google=inbox`. `?google=error&reason=missing_inbox_scope` means the reading boxes were left unticked: the account did connect for sending, so the page still refreshes Settings. Reconnecting an account that reads its inbox asks for reading again, so it doesn't stay paused. A refused change toasts from `app.integrations.google.inbox.patchErrors.*` and redraws the panel from what the server knows (Google may have taken reading back meanwhile). Only mail that arrives after reading is turned on gets read, from the first check a few minutes later.

Nothing here shows while the platform has no Google OAuth client (`configured` false).

## Account (tenant user)

The top-bar avatar (`#btn-account`) opens the signed-in user's account as a `.record` drawer, not a Settings tab: Settings is an optional module, and every user needs a way to their sign-in. It has a `.record-card` (initials, email, role · tenant) and a "How you sign in" `.panel` with two `.worklist__item` rows, Google and Password (dot tone + `.pill--ok` when on, plain pill when off), then a `.hint` with the next step. Each row opens a small form drawer through the drawer trail (back link "Account"); after a change the form closes and the account drawer comes back with fresh data.

- Link Google: current password, then the Google popup. Unlink: current password, `.btn--danger`.
- Password on: change form (current, new, repeat), and below it a `.panel` "Sign in with Google only" whose button confirms with a Google popup (not the password, so the Google account is proven to work before it becomes the only way in).
- Google only: set a password (new, repeat), confirmed with a Google popup; the Google drawer explains it's the only way in and offers "Set a password" instead of unlink.
- Errors go through `app.accountErr_<code>` in the catalogs, falling back to `app.accountFailed`.

## Notifications

The top-bar bell (`#btn-notifications`) is there for every signed-in user, whatever modules the company has. It polls `GET /app/api/notifications` every minute while the tab is visible (and at once when the tab comes back after that). The server stores the kind and its params; `app.js` writes the sentence from `app.notifications.kinds.*`, so the list reads in the reader's language. Only a message someone or an agent wrote (`team.notify`) arrives as text, and it becomes the row's title.

The bell opens a `.record` drawer (eyebrow: the company). A "New" `.panel` (`.tag` count, ghost "Mark all as read" in `.panel__tools`) lists unread rows, and an "Earlier" `.panel` lists read ones. Both are `.worklist.worklist--wrap`. Unread dots follow the kind (approval and paused agent `warn`, failed run and reconnect `bad`, task `info`, message `accent`); read ones are `muted`. With nothing at all, it shows an `.empty` state. A row marks itself read and opens what it's about, with the back link "Notifications": the approval, task, run or agent its `ref` names (`AgentsUI.openRef`; `inbox` goes to the Agents inbox), else its record (`openAgentSubject`), else its page (`link`). An open list redraws in place when a poll changes it.

## Document template studio

Settings → Quote & invoice template is a three-pane studio (layers · A4 stage · inspector), not a stacked form. Script: `app/doc-template.js`. Persist via `PUT /app/api/settings/document-template` (`layout`, `accentColor`, `showDecor`, `style` plus the existing copy fields). Empty `layout` prints `DocumentLayouts.DEFAULT`, the same page the studio starts from. `style` chooses how PdfGenerator paints the page (`classic` accent bar, rounded rows and total pill vs `plain` / `split` / `band` ruled tables). Templates opens a wide drawer of A4 thumbnails (built-in + saved). Applying a design keeps company copy.

Every style shares the same document rules, and the studio preview mirrors them: amounts print as `1 234,56 €`; the accent fills bars, pills and the band, while text in the accent (labels, the document number, section headings) uses it darkened to 4.5:1 on white, so a yellow accent reads as dark gold; text on an accent fill is ink or white, whichever contrasts more. The title line shows the number, the issue date and the validity (quotes with their own date) or due date (invoices); only statuses the client cares about get a badge (paid, overdue, cancelled, accepted). The footer text and, on multi-page documents, "Página X de Y" print on every page. Decoration (classic only) is accent-tinted waves in the top margin of the first page and along the bottom edge.

Defaults when the tenant leaves a text empty: invoices print "Pagamento até à data de vencimento." as payment terms and no terms section; quotes print no payment section (their own notes, if any, fill it) and "Este orçamento é válido por 30 dias." only when the quote has no validity date of its own. A section with no text is not printed at all. The studio preview follows the same rules, from the `defaults` the API sends.

## New dashboard page

1. Add a `nav__item` with `data-tab` / `href`.
2. Add `--tab` color + emoji in `style.css` next to the other tab personality rules.
3. Render into `#view` with hero + panel (or a documented special layout: assistant, calendar, settings).
4. Add i18n keys in all three catalogs.
5. Do not create `page.css` or a new layout grid.

## Responsive

At `max-width: 920px`:

- Sidebar becomes a slide-over; `#btn-nav` + `#nav-scrim` toggle it
- Topbar search stays visible on a second row (`grid-column: 1 / -1`); hide only `kbd`
- View padding shrinks; hero stacks
- Drawer goes full width, square corners

Do not hide primary CTAs or the search field at this breakpoint.

## Widget (not the dashboard)

`widget.css` / `widget.js` is an embed on third-party sites:

- Prefix every class `tbl-`
- Host may set `--tbl-accent`
- Do not depend on dashboard tokens or fonts
- Keep the bubble + panel + message list; do not restyle it to Outfit/Nunito
