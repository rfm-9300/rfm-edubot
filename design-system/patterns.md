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

`/app` and `/backoffice` both declare `<html data-layout="minimal">`, their fixed skin ([tokens.md](tokens.md#layout-skins-htmldata-layout)); there is no layout switch. Every screen of either must read well in the minimal skin in both themes. `/app`'s sidebar foot ends with the "Powered by The Bots Lab" credit ([components.md](components.md#sidebar-kpis)); the backoffice, The Bots Lab's own tool, leaves it out.

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
8. Quote / invoice rows open the drawer for status, convert, or edit; a client row opens the client record, a backoffice tenant row the tenant record — not a new page

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
5. `.dash-tiles` for services, suppliers, employees, catalog, Instagram, assistant (and clients when not already a KPI). The employees tile's meta reads "N to approve" while registered services wait.

Rows deep-link: `data-go` switches module and `data-open` opens that invoice / quote / payment / client / booking. Agent rows (an approval waiting, a task due by the end of today, a run that failed) carry a ref — `approval:ID`, `task:ID` or `run:ID` — and open that approval, task or run over the Agents inbox or activity tab (`AgentsUI.focusRef` picks the tab, `AgentsUI.openRef` the drawer). A task from an earlier day reads "Overdue task" with the `late` tone. A connected Gmail account that Google stopped honouring (`integration_reconnect`, `bad` tone, the account address as detail) carries `data-settings="channels"` and opens Settings → Channels, where an admin reconnects it; the same notice reaches admins in the bell. A service an employee registered (`service_submission`, `warn`, employee · service, amount) carries `data-go="employees"` and opens its detail. A single overdue invoice or payment keeps its own row; two or more collapse into one count row. Money aggregates use whole euros; per-document amounts keep cents. Relative times and dates come from `Intl.RelativeTimeFormat`, never catalog strings.

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

1. Profile card: initials, contact lines (email opens mail, address opens Maps), NIF, "client since · visits · no-shows · last activity", Edit. Then Call, WhatsApp (`wa.me`; a number typed without a country code is Portuguese) and Open chat when a WhatsApp conversation matches the phone (last 9 digits, like bookings), and the staff notes. Under the card, a **Details** `.panel` lists the company's own fields that have a value as `dl.dash-facts` (`dashFacts()`); there is no panel when none has one.
2. Money strip, up to four cells, each only with its module on, in this order: outstanding (a pending invoice past its due date counts as overdue, as on Home), next booking, to invoice (open Serviços), billed. In proposal (open quotes) only fills a free cell.
3. Needs attention, only when something does: overdue invoices, past bookings never marked done or no-show (so never billed), bookings to confirm, open work to invoice (one click to an "Invoice open work" form with the rows ticked), quotes awaiting a reply or not sent yet, invoices due within 7 days.
4. Chip tabs: Activity (upcoming bookings, then the history of bookings, services, quotes, invoices, payments received and the WhatsApp chat) · Bookings · Services · Quotes · Invoices · Emails (while the company has a Gmail account: what went to or came from the client, see [Email (Google)](#email-google)) · Automations (with Agents on: runs in progress, open tasks and recent runs on the client and its documents, each saying what the agent did; "run an agent" for agents run by hand on clients; Pause automations). Every row opens its own drawer.
5. A sticky create bar: New booking, Add service, New quote, New invoice, each prefilled with the client.

Drawers opened from the record show `← client` and return to it (same tab, fresh data) once they save; ×, scrim and Escape leave it. Edit holds name, NIF, phone, email, contact person, address, postal code, city, the company's own fields and notes ("only your team sees these"). The phone field warns as soon as another client has the number, whatever the formatting, and links to them. Phone is unique per tenant for clients, suppliers and employees, so saving the exact same number is refused with a clear message (`409 phone_taken`) instead of a generic failure; the warning is what catches the same number typed differently. A new client opens its record after saving. Staff notes never reach the bot's CRM tools. The NIF prints on quotes and invoices on the client-number line.

**Client fields.** Each company shapes what the directory asks for (`GET`/`PUT /app/api/crm/clients/fields`). Admins get a ghost **Fields** `.btn--sm` after the Active / Archived chips in the directory's `.panel__tools`; members don't. It opens the **Client fields** drawer: an intro `.hint`, a **Standard fields** `.panel` whose `.form__checks` group ticks the required ones (Name and Phone ticked and `disabled`; a change saves at once with a toast, like Settings → Home), and a **Your fields** `.panel` with a `.tag` count and one `.worklist__item` per own field (title: its name; detail: type · choices · required · in the list), or `.empty`. The sticky foot holds **Add field**. A row or Add field opens the field form through the drawer trail (back link "Client fields"): name, type (`.sel`, `disabled` once the field exists, with a `.hint` on why), choices (a `.txt`, one per line, only for Choice), `.form__check` Required (hidden for Yes / no, which is never required) and Show as a column in the client list, then Save, Cancel and a ghost Remove field through `.confirm`. Saving goes back to the drawer with fresh data.

The client form marks the required standard fields with `.req` as the company chose and puts its own fields after City, in their order: text `.inp`, number and date `.inp--mono`, choice `.sel` with an empty first option (a choice since removed stays listed on the clients that have it), and yes / no as a full-width `.form__check` row. Errors name the field ("Fill in "Pet's name""). Own fields marked "show as a column" are list columns by default, after the standard columns and before Created (numbers right-aligned), and the list search matches their values. The names are the company's own words, so they are shown as typed, not translated.

**Directory columns.** Clients, Suppliers and Employees have a ghost **Columns** `.btn--sm` in the directory's `.panel__tools`, after the Active / Archived chips (before Fields on Clients). It opens a **Columns** drawer (eyebrow: the page): an intro `.hint`, a **Standard columns** `.panel` whose `.form__checks` group lists the columns in table order (Name ticked and `disabled`), and on Clients a **Your fields** `.panel` with the company's own fields. A box applies at once, without a toast; the sticky foot holds a ghost **Restore the default columns**. The defaults are the columns each list always had, and an own field's default is its "show as a column" setting. Each person's choice stays in the browser (`localStorage`, per company and user) as on/off overrides of those defaults, so a field added later still follows its own. Columns off by default: a client's contact person, postal code, city, email and NIF; an employee's NIF, date of birth and address. With City shown, the client Address column leaves the city out.

Pausing a client's automations is any member's brake for a client who asked not to be contacted: agents neither start nor carry on anything on the client or its documents until someone resumes (runs already waiting end as cancelled). The card says so, and the Automations tab shows a `.notice--warn` with Resume in place of the pause button and the run row.

## Tenants (backoffice directory + record)

The backoffice's Tenants view is the directory recipe with All / Active / Suspended / Deleted chips. A row shows the name (a `.tbl__open` button) over its slug and company line, the channels as stacked pills (`.tbl__pills`), status, messages, and the last activity as date over time. Its only action is Open dashboard (Restore under Deleted). A click anywhere else on the row, or Enter on the name, opens the tenant **record** in the drawer ([components.md](components.md#record)):

1. Profile card: initials, slug and status pill, then one line with the company relation, language, model and modules in use. Edit, Suspend (Activate when suspended) and Delete sit in the head; a deleted tenant has Restore (or "comes back with …") and a "Deleted on …" warning line instead. Then Open dashboard, Users (first companies only), Agents (with Agents on) and Reload pipeline, all plain buttons, so yellow only marks Activate and Restore.
2. Four `.record-kpi`s: messages, last activity (date, time under it), rate per hour, rate per day.
3. A Channels panel: `dl.dash-facts` with each platform's account and id, and a "No access token" pill where one is missing.

Edit, Users and Agents open with `← tenant` and come back to the record after saving. Suspend, Activate and Restore show the record fresh after their confirm; Delete closes the drawer. A new tenant opens its record once created. Closing returns focus to the row's name. Escape closes a confirm on its own and leaves the drawer behind it.

## Invoices

`/app` Invoices uses the same `.panel__views` switch over a `.panel__filters` row of status chips, and the same `.period-nav` above the hero stats. **By week** and **By month** replace the hero with the selected period: paid, pending, overdue, and total, with a vs-previous hint on the total. The table is the history (`.is-total`, then one row per issued period). The row matching the nav's selected period uses `.is-current`. Cancelled rows stay out of the money columns. The list view hero stays all-time.

Money on invoices counts installments: "paid" is what was received (`paidEur`, part of an invoice paid in installments included) and pending / overdue are what is still owed (`outstandingEur`), never the total of a half-paid invoice. Use the `invoicePaidEur` / `invoiceOutstandingEur` / `invoiceOverdueEur` / `invoiceReceipts` helpers in `app.js`, not `totalEur`, wherever money received or owed is summed (Invoices, Financeiro, the client record).

- **Installments.** An unpaid invoice's detail offers **Pay in installments** (Change installments once split), a form drawer through the trail: a hint with the amount to split, a `.form__row-head` with equal-part buttons (2×, 3×, 4×, 6×, 12×), and a `.lines.lines--installments` editor (number in `.line__index`, amount, due date, remove) whose foot shows the total or what is still to split; a `.hint` under it names the first problem (amounts, dates, sum) and turns `.hint--warn`. Installments already received stay out of the editor. The detail then shows an **Installments** `.panel` (count `.tag`, "X of Y received" `.panel__meta`) with a two-column `.tbl` so it fits a phone: the due date over a `.sub` line ("1/2 · due in 30 days", "1/2 · Received on …"), then, in `.actions`, the amount and a **Mark received** `.btn--accent` (a `.pill--ok` once received), which wrap under each other when narrow; rows carry `.is-paid` / `.is-overdue`. The due date is labelled "Next due"; received and still-owed amounts join `.detail__meta` once something was received. In the list, the row's action receives the next part instead of Mark paid, and the total cell carries a `.sub` line ("In 2 installments", "€200.00 left to pay").
- **Tax office code** (the ATCUD in Portugal): an optional field in the invoice form, a `.detail__meta` item, and a ghost Add / Change tax office code in `.detail__foot` that opens a one-field form drawer. It prints on the PDF.
- **Cancel and delete**, ghost buttons at the end of `.detail__foot`, both through `.confirm` with `.btn--danger`. Cancel is offered only while nothing was received and keeps the invoice (and its number) as cancelled; Delete removes it, and its confirm names the money received when there is some. Both reopen the Serviços rows the invoice billed, and a cancelled invoice frees its quote to be converted again.

## Financeiro (combined money ledger)

`/app` Financeiro is a read-only lens over two other modules — paid invoices (money in) and paid payments (money out) — not a module of its own; it shows in nav whenever Invoices or Payments does (no separate enable toggle), and there's no "New" action. Same recipe as Services/Invoices: `.view__hero` + stats + `.period-nav` + `.crm` panel with `.panel__views` **List / By week / By month**. Unlike Services/Invoices, the `.panel__filters` row (a type chip: All / Recebido / Gasto) only appears in List view — By week/By month always shows both directions together, so a type filter has nothing to narrow there. List is a flat chronological ledger, newest first, merging paid invoices and paid payments by `paidAt` (not `dueDate`/`createdAt` — a transaction belongs to the period it actually landed in). By week/By month rolls that same data into one row per period: received, spent, net, with `.is-total` and `.is-current` exactly like Services/Invoices. The hero's three stats are received / spent / net — List view shows all-time totals (matching how Invoices' list-view stats are all-time), period view shows the selected period with a vs-previous hint on net. Clicking a ledger row or the Overview Financeiro card's Open button both land here now, not on Invoices/Payments directly — Financeiro is the entry point for "how are we doing financially," Invoices/Payments stay the place to act on a specific pending or overdue one.

## Suppliers (vendor directory)

`/app` Suppliers is the clients list, inverted: people and companies the tenant **pays**. Same directory recipe (hero + stats + table). A row opens the supplier **record** ([components.md](components.md#record)): profile card (phone, address, "supplier since · payments · last paid", Edit, Call, WhatsApp), money strip (to pay with the overdue part, next due, paid), needs attention (overdue payments, payments due within 7 days), the payments table (a row opens the payment), and a sticky Add payment prefilled with the vendor. Employees get the same record with their role instead of an address. A new supplier or employee opens its record after saving.

A supplier has **usual services** (description, unit, usual price, or no price when it varies). The supplier form edits them in a `.lines.lines--services` editor with a `.lines__empty` line while there are none; the record shows them as a table above the payments (or an empty panel whose button opens Edit); the directory lists them as a `.sub` line under the name, and search matches them.

## Employees: sign-in and registered services

With Employees and Services on, the employee record adds, in this order: a **To approve** cell first in the money strip (count, the pending total as its sub, `warn` tone while any wait); each pending service in Needs attention (`warn`, "Approve "name"", client · day, amount) between overdue and due-soon payments; an **App sign-in** `.panel` with one `.worklist__item` row (the email, last sign-in as the detail, an On / Off pill; "No sign-in yet" with a hint to give one), `disabled` for members with a `.hint` that only admins change it; then a **Registered services** table (done on, client, service, status pill, total; approved rows `.is-paid`, rejected `.is-draft`). The "since" line counts registered services. The sign-in row opens a form drawer through the trail: email, a password typed or filled by **Suggest one** (shown as text, to hand over), Can sign in (`.form__check`, once there is a sign-in), Save, Cancel, and a ghost Remove sign-in through `.confirm`.

A registered service opens as a **detail** like a service's: client link, status pill and total in `.detail__head`; Done by, done on, registered and approved / rejected (when, and by whom) in `.detail__meta`; the lines table; the notes; then a `.hint` for pending and approved ones ("Approved with changes" when the approver changed it) or a `.notice--warn` with the rejection reason. Pending: **Approve** (`.btn--accent`, one click, the detail redraws as approved), **Change and approve** (the Serviços form, wide, prefilled, saving approves), Open the employee (unless the trail goes back there) and a ghost **Reject**, which opens a small form with an optional reason and a `.btn--danger` submit. Approved: Open the service. The Employees list shows a `.pill--warn` "N to approve" after the name and a To approve hero stat; the nav count turns into the pending count with the alert dot. Home's Needs you rows (`service_submission`) and the bell (`service_submitted`) open the same detail.

With Timesheets on, the record also gets an **Hours** table (this week's shifts: day, start–end, worked, status; the open one `.is-current`, approved ones `.is-paid`; the title carries the week's total) whose rows open the shift detail through the trail, and a **Phone for the time clock** `.panel` with one disabled `.worklist__item` (the phone's name, enrolled and last used, `ok` tone; "No phone set up" otherwise). Admins get a ghost **Revoke** in its tools, through `.confirm`; members read a `.hint` that only admins revoke.

## Employee's own pages (My hours, My services)

An employee's own sign-in renders their pages, not the dashboard: the sidebar has **My hours** (with `timesheets`, first, where the session lands) and **My services** (with the pending count), the top bar keeps search, theme, the account avatar (role "Employee") and Log out, and there is no bell, Home or company switcher. On My hours the sidebar KPIs read today and this week; on My services, pending and approved this month. Recipe: `.view__hero` (pending, rejected, approved this month with its total) + `crmPanel` with All / Pending / Approved / Rejected `.chip`s carrying `.chip__count`, and a table (done on, client, service, total, status). `#btn-new` is **Register a service**: the Serviços form (client, day up to today, optional name, catalog or typed lines, notes) with a hint that the team checks it first, saved as Send for approval. A row opens the detail above without the team's actions: while pending, Edit and a ghost Withdraw (through `.confirm`); a rejected one shows its reason. Any other `#tab` in the address falls back to My services.

## My hours (the employee's time clock)

Recipe: `.view__hero` (today, this week against the company's weekly hours) + the forgotten clock-out `.notice--warn` when the open shift is past the company's longest shift + the `.clock` card ([components.md](components.md#time-clock)) + **My shifts**, a `.tbl--stack` table grouped by week with `.is-day` rows that carry the week's total (day, start–end with breaks as a `.sub` line, worked, site, flag pills, status pill). A row opens the shift detail without the team's actions, with the employee's note (editable until approved).

A punch runs the browser's geolocation on click when the company records location, then posts with `channel: WEB`; the outcome is a toast, and a refusal names the reason (`outside_sites` says how far the nearest site is). The running time ticks every 15 seconds while the page is on screen. Times and minutes join with a no-break space so a cell never wraps inside "8 h 3 min". The mobile app's My hours has the same parts in the same order, with the phone's setup panel under the card.

## Timesheets (the team's hours)

`/app` Timesheets (in Business, after Employees). `.view__hero` with working now, to review, hours this week and over the limits, plus a week `.period-nav`; then `.settings-tabs` chips: **Shifts**, **Work sites**, **Rules**.

- **Shifts**: a "Working now" `.panel` with a `.worklist` (name, since, site, on break; `warn` tone and an overdue pill past the longest shift), then the shifts table: an employee `.sel` and To review / Flagged / Approved / All `.chip`s with `.chip__count`, an accent **Approve N clean** (closed, unflagged, pending) and a ghost **Export CSV** in the tools; rows `.tbl--stack` (employee, day, start–end, worked, site, flag pills, status pill). Then a per-employee totals table (days, worked, over the daily and the weekly hours as `warn` pills, to review); a row opens the employee's record.
- **Shift detail** (drawer, through the trail): the day as the title, `Shift · COL-nnn` as the eyebrow, the employee link and status pill with the worked time in `.detail__head`; `.detail__meta` (day, start, end, breaks, site, approved by); the **Clock record** table (punch with its channel as `.sub`, time, place with a `.tbl__link` to the map and flag pills, verified on which phone); **Changes** as a disabled `.worklist` (reason, before → after, who and when); the note. Actions: Approve (accent), Correct (closed shifts) or Close (open ones), each opening a form with a required reason; Correct has start, end and a `.lines--breaks` editor.
- **Add shift** (`#btn-new`): the same form for a shift nobody punched (employee, day, times, breaks, reason), flagged `MANUAL`.
- **Work sites**: a table (name with the address as `.sub`, radius, client, Active / Off pill, a ghost Map link); admins add and edit in a drawer form (name, address, latitude and longitude, which also accept a pasted "lat, lng" pair, **Use my current location**, radius, client, active).
- **Rules**: one form with the policy selects and the hours, `disabled` for members, ending in a `.notice--info` reminding the company to tell its employees what is recorded.

Flags read as pills with tones, never raw codes (`OUTSIDE_SITE` "Outside the sites", `UNVERIFIED` "Not verified", …); the bell's `time_missed_clock_out` and `time_device_enrolled` open the shift or the employee.

## Payments (outgoing bills)

`/app` Payments is invoices, inverted: bills attached to a **supplier** or, when that module is on, an **employee**. The form uses `.chip` to pick the payee kind, then a `.sel`. A supplier payment uses the catalog line editor; once the supplier is picked, its usual services show above the editor as `.form__check` ticks in a `.form__checks.form__checks--wide` group. Ticking one fills the first empty line or adds one (description, 1, unit, usual price), unticking removes that line, removing the line unticks it, and picking another supplier drops the lines the previous one's services added. An employee payment is one amount and an optional description (default “Payment”), not a catalog line. Opening a payment uses the same detail drawer as an invoice: `.detail__head`, `.detail__meta`, the lines table, `.detail__foot` with Mark paid, then ghost **Cancel payment** (only while unpaid; it stays listed as cancelled) and **Delete** (any state; the confirm names the amount when it was paid), both through `.confirm` with `.btn--danger`, like an invoice's.

Quote, invoice, payment and booking details share these rules: the name in the head links to the client's or payee's record; the meta shows when the document was created and, while unpaid or open, how far away its date is ("due in 3 days", "was due 12 days ago", "expires in 6 days"); a pending document past its due date shows as overdue, as on Home. Documents link to each other (a converted quote shows and opens its invoice and no longer offers Convert, and the server refuses a second conversion with `already_invoiced`; an invoice opens its quote). The quote detail has the PDF button like the invoice. Every link goes through the drawer trail, so Back and saving return to where the user came from. Recipe: `.view__hero` + paid / to-pay / overdue stats + `.crm` panel with a compact `.sel` payee filter in `.panel__tools` plus status chips. Mark paid in the row or drawer. No PDF in v1. Creating a payment with neither suppliers nor employees opens the supplier form first.

## Conversation / assistant

Two-column `.assistant` on desktop; stacks at `760px`, where the conversations scroll sideways above the chat. Transcript uses `.chat__*`. The page lives in `app/assistant.js` (`AssistantUI`, mounted by `app.js` like Agents); its strings are under `app.assistant.*`.

- The hero carries a Settings button (in `.actions`, so it keeps its size on phones). The drawer holds the instructions (with a character count), reply style and language `.sel`s, a `.form__check` for "Let the assistant propose changes", and the areas it may use as `.form__checks`. Style options are short names; a `.hint` under the select explains the chosen one, since a half-width select clips long option text. Members see it read-only with a `.hint--warn`, and no Save.
- Tool-call confirmation uses `.assistant__action` (accent border, confirm + cancel). Do not auto-execute. The title and details name what the change is about from the action's `preview` (client, quote, invoice, booking, contact, totals, dates), never raw ids, and don't repeat a name the title already gives. A reply to a customer shows its exact text in `.assistant__quote`. Status pills: waiting `--warn`, running `--info`, done `--ok`, failed `--bad`, cancelled and not done plain. A card the person moved on from turns "Not done" with a hint and loses its buttons. A failed one gives the dashboard's own reason for its error code (the inbox's and bookings' messages), never the tool's text.
- After a confirmation, `.assistant__action-buttons` opens what it made or changed (client, quote, invoice, booking, payment, conversation) through the same openers as Agents, and for a quote or invoice reuses `.pdf` so the user can download the document.
- A turn without an answer explains why (the model is unavailable, the month's AI allowance is spent, no answer came) and the last one offers "Try again", except when the allowance is spent.

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

The backoffice manages a company's agents from its tenant record ([Tenants](#tenants-backoffice-directory--record)): with Agents on, the record's action row gets an **Agents** button that opens a drawer on that company, with the way back to the record. Strings live under `backoffice.agents.*`; status labels reuse `app.agents.status.*`.

1. A `.notice` on the pause state: plain while agents run, `--warn` while the platform or the company paused them. Its one action is Pause agents (`.btn--danger`, through the confirm) or, once the platform paused them, Resume agents (`.btn--accent`). A company's own pause is only described: its admins lift it in Settings, and pausing here as well keeps the agents paused.
2. A "Last 7 days" panel: `dl.dash-facts` of runs, succeeded, failed or waiting for review, waiting or in progress, and approvals waiting now.
3. A `.tbl` of the company's agents: name (with a muted pause reason under it), status pill, runs, failed, last run; `.empty` when there are none.
4. A Limits panel: active agents allowed, runs per day, emails per day, saved by the drawer's Save. A cleared field keeps its limit rather than reading as 0, which allows none. The company sees these read-only in Settings, and a platform pause as a notice without Resume.

The nav badge counts pending approvals plus open tasks. Record links (client, quote, invoice, payment, booking, service, chat) go through `app.js`'s `openAgentSubject` with a drawer-trail back link to the run or approval; there's no "Open record" when the trail already goes back to that record.

Agents also show up where the work is: Home's Agents card and Needs-you rows (see Home (minimal layout)); the client record's Automations tab; the same block under quote, invoice and booking details (only when an agent has run there, is waiting there, or can be run there by hand); the Persona page, which lists the agents with "Write with AI" steps and a "Follows it" check per row (admins only; hidden when there are none); and the top-bar notifications (see Notifications). After "Run now" there, the block looks again every 1.5 s (up to five times) until the run is no longer queued or running, so it ends on what happened.

## Persona (how the bot talks to customers)

`/app` Persona is a studio, not a stack of forms: `.persona-studio` ([components.md](components.md#persona-studio)) with chip tabs on the left and a test chat on the right. Strings live under `app.personaStudio.*`; status, option, change and error labels come from server keys there, never from server text.

1. `.view__hero` with four stats: status (Not set up / Synthesizing… / Live / Needs attention), version, tokens per reply, updated (relative time).
2. `.notice`s under the hero, one per state: `--info` while synthesizing or while sources wait (with Synthesize now), `--warn` when the last synthesis failed (the reason from `errors.*`, "customers keep getting the previous version", Try again), `--warn` when a removed source is still in the instructions (Rebuild from sources), and a plain one for members ("only admins change the persona"). Admins get the buttons; members see the same notices without them.
3. **Behavior**: one `form.form` of `.panel`s: Identity and language (bot name, language `.sel` with "The customer's language" first, a `.form__check` to hold it), Style (tone, form of address, reply length, emoji as `.sel`s with "Not set" first, greeting `.txt`), Rules (a `.txt`, one per line, its count in the head's `.tag`), Handoff to a person (a `.form__check`, when and message `.txt`s, an On/Off pill in the head). Save settings and a ghost Discard changes end the form. The agents that follow the Persona (`.persona-agents`) come last.
4. **Knowledge**: Teach the bot (a note `.txt` with a live "n of max characters" `.hint` that turns `--warn` past 80% and `--bad` past the limit, and a file input whose `accept` and hint come from the server's limits), then the Sources `crm`-style `.tbl.tbl--stack` (label over a `.sub` kind · size · who added it; an In the instructions / Waiting pill; date; View and Remove) with a ghost Rebuild from sources in the head and the usage line under it. Remove confirms through `.confirm`, saying whether the instructions change.
5. **Instructions**: the instructions `.txt` (read-only for members) with its character and token counter, Save and Discard; then "What the bot reads", a read-only `.txt` with the exact `<persona>` block (`POST /app/api/persona/preview`, unsaved changes included) and its token estimate.
6. **History**: a `.tbl.tbl--stack` of versions (the live one `.is-current` with a Live pill): change, by, when, View (a wide drawer with `dl.dash-facts` of the settings and the read-only instructions) and Restore through `.confirm`.
7. **Test the bot**: a `.panel` with "Try" `.chip`s (who are you, hours, prices, a person, a prompt-injection attempt, another language) that send at once, the `.chat__log`, and the composer. Unsaved edits in any tab go with each test message and show the head's `.pill--warn` "Unsaved changes". A handoff shows as a `.chat__note` under the bot's reply; failures are `.chat__note`s too, not toasts.

Unsaved edits live in `state.personaDraft` (behavior, instructions, the note being typed), so redraws (the 3-second poll while synthesizing, tab switches) keep them, and `renderKeepingFocus` keeps the caret where it was. A synthesis that ends toasts the new version, or the failure.

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
- An employee's own sign-in has the same drawer (role "Employee"); admins set its email and first password from the employee record.

## Notifications

The top-bar bell (`#btn-notifications`) is there for every signed-in user, whatever modules the company has, except an employee's own sign-in. It polls `GET /app/api/notifications` every minute while the tab is visible (and at once when the tab comes back after that). The server stores the kind and its params; `app.js` writes the sentence from `app.notifications.kinds.*`, so the list reads in the reader's language. Only a message someone or an agent wrote (`team.notify`) arrives as text, and it becomes the row's title.

The bell opens a `.record` drawer (eyebrow: the company). A "New" `.panel` (`.tag` count, ghost "Mark all as read" in `.panel__tools`) lists unread rows, and an "Earlier" `.panel` lists read ones. Both are `.worklist.worklist--wrap`. Unread dots follow the kind (approval, paused agent and registered service `warn`, failed run and reconnect `bad`, task `info`, message `accent`); read ones are `muted`. With nothing at all, it shows an `.empty` state. A row marks itself read and opens what it's about, with the back link "Notifications": a registered service (`ref` `submission:ID`) opens its detail; otherwise the approval, task, run or agent its `ref` names (`AgentsUI.openRef`; `inbox` goes to the Agents inbox), else its record (`openAgentSubject`), else its page (`link`). An open list redraws in place when a poll changes it.

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

Phones:

- At `700px` a `.tbl--stack` table shows one card per row, each value under its `data-label`, instead of scrolling sideways. Give every list table on a page people open from a phone `.tbl--stack` and label its cells.
- At `560px` forms drop to one column, the drawer's padding to 16px, line items and channel rows stack, and a long toast uses the screen's width.
- On touch screens fields are 16px, so iOS doesn't zoom in on focus, and the backoffice opens a drawer with focus on × rather than in a field, so the keyboard stays closed.

## Widget (not the dashboard)

`widget.css` / `widget.js` is an embed on third-party sites:

- Prefix every class `tbl-`
- Host may set `--tbl-accent`
- Do not depend on dashboard tokens or fonts
- Keep the bubble + panel + message list; do not restyle it to Outfit/Nunito
