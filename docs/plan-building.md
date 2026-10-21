# Implementation Plan — Building (construction projects and their progress)

Status: **Phase 1 implemented** (server, `/app` pages, Home, the client's progress page), **Phase 2 in
progress** ([§10](#10-phase-2-design)) · Owner: Rodrigo · Last updated: 2026-10-21

A new nav category, **Building**, for construction and renovation companies: the projects (obras) a
company runs and how far along each one is. The schedule and its phases, the site diary, photos,
issues on site, changes to the contract, and the measurements that bill the work as it gets done.
The client follows their project from a link the company sends them, by WhatsApp or email.

This plan was written without access to Rodrigo's personal wiki or GitNexus (cloud VM), so it rests
on the repo docs and the repo's `wiki/` alone. The as-built notes are in
[wiki/building-projects.md](../wiki/building-projects.md).

---

## 1. What the main products do

| Product | Market | Progress tracking | Field | Money | Client |
|---|---|---|---|---|---|
| [Procore](https://www.procore.com/project-management) | Mid to large GCs | Schedule (Gantt, lookahead), daily log tied to scheduled work | [Daily log](https://support.procore.com/products/online/user-guide/project-level/daily-log/tutorials/daily-log-overview): weather, manpower, notes, equipment, deliveries, delays, photos; [punch list](https://www.procore.com/project-management/punch-list) with drawing pins | Budgets, commitments, change orders, pay applications | Owner/sub logins (unlimited users) |
| [Buildertrend](https://buildertrend.com/project-management/) | Residential builders, remodelers | Calendar/Gantt schedule visible to the client | Daily logs with photos and weather, to-dos, warranty | Change orders with e-approval, selections, invoices | [Client portal](https://buildertrend.com/communication/construction-client-portal/), AI weekly update |
| [Fieldwire](https://www.fieldwire.com/blog/best-construction-management-software/) (Hilti) | Specialty contractors | Task lists with due dates | Tasks pinned on plans, punch lists, inspections, offline | Change orders and budget only on the top tier | — |
| [Autodesk Forma Build](https://www.autodesk.com/products/forma-build/product-details) | GCs | Schedule, progress tracking on sheets | Issues, daily reports, forms, photos, quality/safety | Change management, payment applications | — |
| [JobTread](https://www.jobtread.com/) | Small/mid builders | Gantt, tasks | Daily logs, photos, time tracking | Budget vs actual, change orders, AIA pay apps | Customer portal with e-signatures and payments |
| [CompanyCam](https://companycam.com/features/galleries-timelines) | Trades | — | Time/GPS-stamped photos, checklists, AI daily report | — | Live **project timeline link**, no account needed, can be disabled |
| [Raken](https://www.rakenapp.com/features/daily-reports) | Superintendents | Production quantities vs budget | Daily report: work logs (crew, workers, hours), notes, photos, weather, PDF | — | — |
| [Empreitek](https://empreitek.pt/), [Obra Sys](https://obrasys.pt/), [Livro de Obra](https://www.livrodeobra.pt/), [ForConstruction](https://aiobi.pt/pt/solucoes/construcao) | Portugal | Cronograma planned vs executed | Diário/livro de obra (meteorologia, trabalhadores, equipamento, trabalhos, ocorrências), não conformidades com fotos | **Autos de medição** from the awarded quote (type the % executed), trabalhos a mais e a menos, invoice from an approved auto | Reports to the dono de obra |
| [Dibber](https://dibber.es/), [ObraERP](https://obraerp.com/), [Fixner](https://fixner.com/software-construccion) | Spain | Avance físico y económico | Partes diarios, fotos de avance | **Certificaciones a origen** por partidas, modificados, retenciones | — |

What they converge on, and what Phase 1 takes:

1. **A project record** with its client, site, contract value, dates and a status.
2. **A schedule of phases** with planned dates and a progress %, shown as a Gantt bar against today,
   and the project's progress as their weighted average, compared with where the plan says it should be.
3. **A daily site diary**: weather, people on site, work done, equipment, materials, visitors,
   occurrences and time lost, with photos, one per day per project, that also moves the phases on.
4. **Photos** that belong to the project (and to a diary day or an issue).
5. **Issues / punch list**: what is wrong or still to do, who fixes it, by when, its status history.
6. **Change orders** (trabalhos a mais e a menos): extra or omitted work with its value and the days
   it adds; approved ones change the contract and the end date.
7. **Progress billing** (autos de medição, certificaciones, pay applications): measure each contract
   line's executed quantity to date, bill the difference since the previous measurement, hold a
   retention, and issue the invoice from it.
8. **The client follows along** from a link that needs no account (CompanyCam's timeline, Buildertrend's
   portal) — here sent over WhatsApp, the product's own channel.

What we deliberately do differently, or later:

- **No drawings, RFIs, submittals or BIM.** They serve commercial GCs with design teams; the
  product's customers are small builders and renovators. Phase 3 at the earliest.
- **No offline app.** `/app` works in the phone's browser; the KMP app can add offline capture later.
- **Measurements work on the quote's lines** (the bill of quantities a Portuguese or Spanish client
  signs), not on a separate schedule of values, and **to date** (cumulative), as every PT/ES tool and
  the AIA method do, so a figure typed wrong last time is corrected by the next one.
- **The client link is read-only.** Approving a change from the link (an e-signature) needs its own
  signed token per document; Phase 2.

## 2. Product scope

### People

- **The team** — admins and members of a company with the Building modules. Everything below.
- **The client** — opens the project's link: progress, phases, planned completion, the updates and
  photos the team chose to share. No account, nothing to sign in to.
- **Employees** (an employee's own sign-in) — no access in Phase 1. A foreman's "My sites" page is
  Phase 2.

### Modules

Five opt-in modules in a new **Building** nav group, after Business:

| Module | Page | Brings |
|---|---|---|
| `projects` | Projects (Obras) | — (the others need it, and bring it) |
| `site-diary` | Site diary (Diário de obra) | `projects` |
| `site-issues` | Issues (Ocorrências) | `projects` |
| `change-orders` | Changes (Trabalhos a mais) | `projects` |
| `measurements` | Measurements (Autos de medição) | `projects` |

All are in `DashboardModules.optIn`: a cleaning company or a clinic with no saved selection must not
suddenly see projects in its menu.

### Stories (Phase 1)

1. I create a project from an accepted quote: its client, its lines as the contract and its value
   come along; or I type one in.
2. I plan it in phases with dates and see it as a Gantt, with today and where each phase should be.
3. On the projects page I see every project's progress against its plan, how many days ahead or
   behind it is, and which ones are delayed; a timeline shows them side by side.
4. At the end of the day I write the site diary: weather, how many were on site, what was done,
   what arrived, what went wrong, the hours lost, and move the phases' progress; I add photos.
5. I see which active sites have no diary today.
6. I log an issue with a photo, assign it to an employee or a subcontractor, give it a due date, and
   follow it from open to closed.
7. I record a change the client asked for, with its lines, value and the days it adds, send it, and
   record the client's answer; approved, it raises the contract and moves the end date.
8. At the end of the month I measure how much of each contract line (and approved change) is done;
   the measurement says what is billed now, what was before, the retention and the net; I approve
   it and issue the invoice in one click.
9. I turn on the project's link and send it to the client by WhatsApp; they see the progress, the
   phases, the planned end, and the diary days and photos I chose to share. I can turn it off.
10. Home tells me which projects are delayed, which issues are overdue, which changes wait for the
    client and which measurements wait to be invoiced.

## 3. Concepts and rules

### Projects

- **Status**: `PLANNING` → `ACTIVE` ⇄ `ON_HOLD` → `COMPLETED`; any → `CANCELLED`. Starting stamps the
  actual start (today) when none was typed; completing stamps the actual end.
- **Number**: `OB-001`, one sequence per company, like `ORC-` and `FAT-`.
- **Contract**: the lines of the quote it came from, or typed ones (description, quantity, unit,
  price), each with a stable id so measurements can refer to it. Locked once a measurement exists.
- **Current contract** = contract + approved additions − approved omissions.
- **Revised end** = planned end + the days approved changes add.

### Progress

- A phase's **progress** is 0–100, moved by hand or from the diary.
- A phase's **weight** is the one typed; without one it weighs as many days as it is planned to last
  (one day with no dates). The project's **progress** is the weighted average; a project without
  phases keeps a progress typed on the project.
- **Planned progress** on a day: each phase is planned to go from 0% at its planned start to 100% at
  its planned end, linearly; the project's is the weighted average of the phases that have dates
  (or, with none, the project's own planned start and end).
- **Days ahead or behind** (earned schedule): the day on which the plan reaches today's actual
  progress, minus today. Behind by 4 days means today's progress was planned for 4 days ago.
- A phase is **late** when its planned end has passed and it isn't done, or when it is 20 points or
  more behind its planned progress.
- A project's **health** (active ones only): *delayed* when its revised end has passed or it is more
  than max(7 days, 10% of its duration) behind; *at risk* when it is more than max(2 days, 3% of its
  duration) behind or a phase is late; otherwise *on track*.

### Site diary

- One entry per project per day (unique index), never in the future (company timezone).
- Weather (clear, cloudy, rain, wind, storm, fog), temperature, workers on site, work done (required),
  equipment, materials received, visitors and inspections, occurrences, hours lost.
- Phase progress typed in the entry is applied to the project and kept on the entry as from → to.
- *Share with the client* puts the day (work done and its shared photos) on the client's page.

### Photos

- The browser shrinks each photo (longest side 1600 px, JPEG) and makes a 480 px thumbnail, and
  uploads both: the server stores JPEG only, decodes nothing and stays out of `java.awt` on the
  Alpine JRE.
- Files go on the PDF volume beside the company's logo: `<PDF_STORAGE_PATH>/<slug>/building/<projectId>/`.
  Like the logo they are **outside the Mongo backups** (see §9).
- A photo belongs to a project and optionally to a diary day, an issue or a phase. Diary and gallery
  photos are shared with the client by default; issue photos are not.

### Issues

- `OC-001`; kind (punch item, defect, safety, other), priority (low, medium, high), location, phase,
  due date, assigned to an employee or a supplier (subcontractor).
- `OPEN` → `IN_PROGRESS` → `RESOLVED` → `CLOSED`, and back to open; every change is kept with who,
  when and a note. Overdue: past its due date while open or in progress.

### Change orders

- `TM-001`; an **addition** or an **omission**, lines like a quote's, days added (may be negative).
- `DRAFT` → `SENT` → `APPROVED` / `REJECTED`; a draft can be approved directly (the client said yes on
  site). Editable while draft or sent. An approved change reopens only while no measurement has
  measured its lines; a rejected one can be reopened as a draft.

### Measurements (autos de medição)

- Numbered per project (n.º 1, 2…). Lines: every contract line plus every approved change's lines;
  each measurement stores, per line, the quantity done **to date**; this period = to date − the
  previous measurement's to date.
- Refused: to date above the contracted quantity (record a change instead) or below the previous
  measurement's (a correction of an invoiced figure needs the invoice cancelled first).
- Value to date of a line: its full total once fully measured, otherwise quantity × unit price,
  rounded to the cent. Retention = this period × the project's retention % (stamped on the
  measurement); net = this period − retention.
- One draft per project at a time; only the latest measurement can be reopened or deleted, and not
  once invoiced. `DRAFT` → `APPROVED` → `INVOICED`.
- **Invoice**: one line per contract line billed this period plus a negative retention line, linked
  both ways (`invoice.projectId` / `measurementId`, `measurement.invoiceId`). Cancelling or deleting
  that invoice puts the measurement back to approved.
- **A quote is billed once.** A project's first measurement marks its quote (`quote.projectId`), and
  `QuoteInvoicing.billedBy` then refuses invoicing or servicing the quote directly
  (`already_measured`); a measurement is refused while the quote is already billed directly
  (`quote_billed`). Deleting the project's last measurement clears the mark.

### The client's page

- `https://<domain>/app/share/<token>`: under `/app` so the production proxy routes it as it is
  (the website's Caddy only forwards listed prefixes). 32 random bytes, base64url; on, off, or
  replaced by a new one (the old link stops working).
- Shows: company name and logo, project name and number, status, progress, planned completion
  (revised end), the phases (name, dates, progress, done/in progress/not started), the shared diary
  days with their shared photos, and a photo gallery. Never: money, issues, internal notes, people.
- `noindex`, no cookies; the page loads the shared stylesheet and catalogs and shows the company's
  language.

## 4. Data model

All in the data database (`wabot`), every document with its `tenantId`
([mongo-databases.md](../wiki/mongo-databases.md)), so `restore-tenant.sh` restores them with the company.

| Collection | Holds | Indexes |
|---|---|---|
| `building.projects` | project, embedded `phases[]`, `contract[]`, `share{token, enabled}`, `version` | `tenantId+status+updatedAt`, `tenantId+number` (unique), `tenantId+clientId`, `tenantId+quoteId`, `share.token` (unique, partial) |
| `building.site_logs` | diary entries, `progress[]` (phase from → to) | `tenantId+projectId+day` (unique), `tenantId+day` |
| `building.photos` | photo metadata, file paths | `tenantId+projectId+createdAt`, `tenantId+logId`, `tenantId+issueId` |
| `building.issues` | issues, `history[]` | `tenantId+projectId+status`, `tenantId+status+dueDate`, `tenantId+number` (unique) |
| `building.change_orders` | change orders, `items[]` | `tenantId+projectId+status`, `tenantId+number` (unique) |
| `building.measurements` | measurements, `lines[]` | `tenantId+projectId+number` (unique), `tenantId+status`, `tenantId+invoiceId` |

Changes to existing models: `Quote.projectId` (the project whose measurements bill it),
`Invoice.projectId` and `Invoice.measurementId`.

## 5. API

Everything under `/app/api/building`, one dashboard feature per module behind its gate (invoicing a
measurement nests the `invoices` gate), on services in `building/` that never touch HTTP, as
[wiki/dashboard-api.md](../wiki/dashboard-api.md) lays out; an employee's sign-in never reaches it.
Refusals follow the error contract. Money in euros in JSON, cents in Mongo, like the CRM.

```
GET    /projects                     list with progress, health and money
POST   /projects                     create (optionally fromQuote)
GET    /projects/{id}                the record: phases with computed fields, contract, money, counts
PATCH  /projects/{id}                edit (contract locked once measured)
DELETE /projects/{id}                with its diary, photos, issues and changes; refused once measured
PUT    /projects/{id}/phases         replace the phases (the editor saves them together)
PATCH  /projects/{id}/phases/{pid}   progress of one phase
POST   /projects/{id}/share          turn the link on (DELETE turns it off, /share/rotate replaces it)
GET    /projects/{id}/photos         POST multipart: file + thumb (+ logId, issueId, phaseId, caption, shared)
GET    /photos/{id}?size=thumb       PATCH caption/shared, DELETE
GET    /diary?projectId&from&to      POST, GET/PATCH/DELETE /diary/{id}
GET    /issues?projectId&status      POST, GET/PATCH/DELETE /issues/{id}
GET    /changes?projectId&status     POST, GET/PATCH/DELETE /changes/{id}, POST /changes/{id}/status
GET    /measurements?projectId       POST, GET/PATCH/DELETE /measurements/{id}
POST   /measurements/{id}/approve    /reopen, /invoice {dueDate}
```

Public: `GET /app/share/{token}` (the page), `/data`, `/photos/{id}`, `/logo`, and
`/app/share/assets/{file}` (its script).

## 6. Web (`/app`)

- **Projects**: hero (active, at risk or delayed, open issues, to invoice) and a `crmPanel` with a
  List / Timeline view switch. List: number and name over the client, the progress bar (actual with
  the planned mark), days ahead or behind, revised end, health pill. Timeline: one Gantt row per
  project across the months, today marked. Chips: Running / Planning / Finished / All.
- **Project record** (wide drawer): record card (client link, address on Maps, dates, share), four
  KPIs (progress vs plan, schedule, contract, measured), Needs attention (late phases, overdue
  issues, changes waiting for the client, measurements to invoice, no diary for 3+ days), chip tabs
  Schedule · Diary · Photos · Issues · Changes · Measurements · Contract, and a sticky create bar.
- **Schedule**: the phases as a Gantt with their status and progress; Edit phases opens the editor
  (name, planned start and end, weight, progress), Update progress a quick form.
- **Site diary**: Today (each active project, reported or not, one tap to write it) above the entries
  of the last 30 days, filterable by project.
- **Issues**, **Changes**, **Measurements**: list pages with status chips over every project, their
  detail drawers and forms, linked to the project and back through the drawer trail.
- **Home**: a Building card (active, behind, open issues; the projects furthest behind; diaries
  today; to invoice; changes waiting) and Needs-you rows for delayed projects, overdue issues,
  changes waiting for the client and measurements to invoice.
- Quote detail: **Start a project**; invoice detail: the project and measurement it came from.

## 7. Phases

1. **Phase 1 (PR #88)** — everything above.
2. **Phase 2** — a foreman's **My sites** page on an employee's sign-in (diary, photos, issues on their
   projects only); **job costing**: supplier bills and time-clock hours by project, budget vs actual
   and margin (Obra Sys, Empreitek, JobTread); **client approval of a change** from the link, with its
   own signed token and a typed name; a **weekly client update** drafted by the AI from the diary
   (Buildertrend); retention **release** at closeout; domain events so Agents can act on a delayed
   project, an overdue issue or an approved measurement; PDF of a measurement and of the diary.
   Designed in [§10](#10-phase-2-design).
3. **Phase 3** — drawings with issue pins, inspections and checklists, the KMP app offline capture,
   BC3 import (Spain), weather filled in from a forecast service.

## 8. Testing

- `ProjectProgressTest`: weights, planned curve, earned-schedule days, phase status, health.
- `MeasurementMathTest`: lines from contract and changes, to date and this period, rounding, retention,
  refusals, invoice lines.
- `BuildingApiTest` (HTTP over the whole server, Mongo): each page's gate and the modules each brings,
  isolation between companies, the error contract, a project from a quote, phases and progress from
  the diary, one diary entry per day, photos stored and served (JPEG only), issues through their
  statuses, a change's effect on the contract and the next measurement, a measurement approved and
  invoiced and released when its invoice is cancelled, the quote billed once both ways, Home's card and
  Needs-you rows, and the public link showing only what it should. `DashboardRouteAuditTest` checks
  every Building route's gate, and `ArchitectureBoundariesTest` that `building/` stays free of HTTP.

## 9. Open points for Rodrigo

- Photos live on the `pdf_data` volume, outside the nightly Mongo backups, like the company logo.
  A volume backup (or object storage) becomes worth it once companies rely on their photo history.
- The public page is under `/app/share/…` so it needs no Caddy change; a shorter `/p/…` would need a
  `handle /p*` route in the website's Caddyfile.

## 10. Phase 2 design

Same structure as Phase 1: services in `building/`, routes in `dashboard/building/`, every string in the
three catalogs, HTTP tests per area. Built in this order, one commit each.

### 10.1 Domain events, for Agents

| Subject | Events |
|---|---|
| `project` | `project.created`, `project.status_changed` (from → to), `project.at_risk`, `project.delayed` |
| `site_log` | `site_log.created` |
| `site_issue` | `site_issue.created`, `site_issue.status_changed` (from → to) |
| `change_order` | `change_order.sent`, `change_order.decided` (to `APPROVED` or `REJECTED`, `via` the team or the client's link) |
| `measurement` | `measurement.approved`, `measurement.invoiced` |

- Each event names the project and its client as related records, so the client's activity timeline
  shows it and an agent's actions (a WhatsApp to the client, a task, a notice) have the client.
- The services append the events after their own writes. Health is computed, not written, so an hourly
  job (on a lease, like the agents' scheduler) stores each active project's health and appends
  `project.at_risk` or `project.delayed` when it turns so: an agent can tell the manager that day. Two
  events rather than one "health changed" with a status filter, whose choices would be the project's
  statuses, not its health.
- The agents' context builder loads each new subject (the project's figures, the issue, the change, the
  measurement, the diary day) with its project and client; the Agents page lists the events in the
  company's language and offers each one's statuses.

### 10.2 The client approves a change from a link

- Sending a change to the client gives it its own link, `/app/share/change/{token}` (32 random bytes).
  The page shows the company, the project, the change's lines, value and days, and asks for the
  client's name to approve or reject it, with an optional note.
- The link works while the change is sent. Editing a sent change takes it back to draft and ends the link,
  so the client never approves something other than what they read; deciding from the dashboard ends it
  too. A decision records the typed name, the time and that it came by the link, notifies the team (the
  bell) and appends `change_order.decided`.
- The change's drawer shows the link (copy, send by WhatsApp, open) while it waits, and who decided.

### 10.3 Releasing the retention

- Once the project is finished, the retention held (the retention of its invoiced measurements, less
  what was released) is invoiced in one line to the project's client. The project keeps the releases;
  cancelling or deleting that invoice frees the amount again (`InvoicedWork`).
- `POST /building/projects/{id}/retention/invoice {dueDate}` needs measurements and invoices.

### 10.4 PDFs

- **Measurement** (`GET /building/measurements/{id}/pdf`): the company's logo, name and colour; project,
  client, number and period; per line the unit, contracted, previous, this period, to date, % and value
  this period; the totals, retention and net; signature boxes for the contractor and the client.
- **Diary** (`GET /building/diary/pdf?projectId&from&to`): the project's days in the period with
  weather, people, work done, the other notes, the phases each day moved and up to six photos.
- Both in the company's language, from `building/BuildingPdf.kt` over PDFBox with the bundled fonts;
  the quote and invoice generator is left alone.

### 10.5 Job costing

- A payment can name the project it was for (`crm.payments.projectId`), like the client it was for; the
  Payments form offers the projects with the projects module.
- A work site can belong to a project (`timesheets.sites.projectId`): shifts clocked at it are the
  project's hours.
- A project can have a cost budget and a labour cost per hour (both optional).
- A **Costs** tab: revenue (current contract, measured, invoiced), costs (bills by supplier, paid and to
  pay; hours by employee at the project's rate), the budget against the costs, and the margin, expected
  (current contract less costs) and to date (measured less costs). `GET /building/projects/{id}/costs`;
  bills need payments, hours need the time clock.

### 10.6 A weekly update for the client

- `POST /building/projects/{id}/updates/draft {from?, to?}` (the last 7 days by default): the AI writes a
  short update from the shared diary days, the phases' progress and approved changes, in the company's
  language, inside its monthly token budget (`409 token_budget` past it), recorded as `building` usage.
- The team edits and posts it (`building.updates`); the client's page shows the posted updates, newest
  first. Copy and WhatsApp buttons send it too.

### 10.7 My sites, for the site manager

- An employee page, `my-sites`, when the company has employees and the site diary or issues: the active
  projects whose manager they are.
- Under `/app/api/portal/sites`: the list, a project (phases, today's entry, open issues, recent photos),
  writing and editing the diary (moving phases), uploading photos, logging issues and moving their status.
  The same services, stamped with the employee's name; no money anywhere.

### 10.8 Testing

HTTP tests per area: events appended with their related records and loaded by the agents' context;
the change link (decide once, editing ends it, isolation, headers); the retention invoice and its release;
both PDFs parse and carry the figures; costs from linked bills and hours; the draft with `FakeModel` and
the budget refusal; My sites only for the manager's projects, refused to the team.
