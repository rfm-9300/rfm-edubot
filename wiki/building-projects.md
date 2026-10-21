---
updated: 2026-10-21
---

# Building: projects and their progress

The opt-in category for construction and renovation companies: **Projects**, **Site diary**,
**Issues**, **Change orders** and **Measurements**, a Projects card on Home, a read-only page the
client opens from a link, and **My sites** on a site manager's own sign-in. Phase 1 is PR #88
(2026-10-20); phase 2 (events for agents, change approval by link, the retention, PDFs, job costing,
client updates, My sites) is PR #89 (2026-10-21). The plan, with the market research behind it
(Procore, Buildertrend, Fieldwire, JobTread, CompanyCam, Raken, and the Portuguese and Spanish tools
built around autos de medição and certificaciones), is [`docs/plan-building.md`](../docs/plan-building.md);
the structure and diagrams are in [`docs/architecture.md` → Building](../docs/architecture.md#building).

## Modules and where the code is

| Module | Page | Brings |
|---|---|---|
| `projects` | Projects: list, timeline, record with phases, contract, photos and the client's link | — |
| `site-diary` | Site diary | `projects` |
| `site-issues` | Issues | `projects` |
| `change-orders` | Change orders | `projects` |
| `measurements` | Measurements | `projects` |

- All five are opt-in (`DashboardModules.optIn`), so no company gets them with the legacy full
  catalog: a clinic must not find site diaries in its menu. Each page brings `projects`
  (`effectiveFor`), and the route audit's `without()` drops the four pages when it turns `projects` off.
  Measurements don't bring invoices: a company may bill elsewhere, and invoicing a measurement nests
  the `invoices` gate.
- Backend, as [dashboard-api.md](dashboard-api.md) lays out: `building/` holds the models,
  repositories, the progress and measurement math, the drafts and the services (`ProjectService`,
  `SitePhotoService`, `SiteDiaryService`, `SiteIssueService`, `ChangeOrderService`,
  `MeasurementService`, `ProjectCostsService`, `ProjectReportService`, `BuildingDocuments` over
  `BuildingPdf`), the events (`BuildingEvents`, `ProjectHealthWatch`), and imports nothing from Ktor's
  server (`ArchitectureBoundariesTest`). `building/api/` has the requests, DTOs and views;
  `dashboard/building/` the six features (`ProjectReportRoutes` is the sixth), the client's pages
  (`ProjectShareRoutes`, `ChangeApprovalRoutes`) and My sites (`MySitesRoutes`); Home's card is
  `dashboard/overview/ProjectsOverview.kt`.
- Frontend: `building.js` (what the pages share: labels and pills, the progress bar and the timeline,
  the project picker, shrinking, uploading and viewing photos, the nav counts), `projects.js`,
  `site-diary.js`, `site-issues.js`, `change-orders.js`, `measurements.js`, `my-sites.js`
  ([dashboard-frontend.md](dashboard-frontend.md)); the client's pages are `share/index.html` +
  `share/share.js` and `share/change.html` + `share/change.js`. The components are in
  [`design-system/components.md`](../design-system/components.md) → Building.
- Collections, all in the data database with `tenantId`: `building.projects`, `building.site_logs`,
  `building.photos`, `building.issues`, `building.change_orders`, `building.measurements`,
  `building.reports`. Numbers come from `crm.sequences`: projects `OB-001`, issues `OC-001`, change
  orders `TM-001`; measurements are numbered per project.

## Progress against the plan (`ProjectProgress`)

- A project's progress is its phases' progress weighted by the weight typed on a phase, else its
  planned days. Without phases it is the percentage typed on the project.
- The plan is read **as of the end of yesterday**: progress is reported at the end of a working day,
  so a phase that starts today isn't behind yet. A phase's plan goes linearly from 0% the day before
  its start to 100% at the end of its last day.
- **Days ahead or behind** are earned schedule: the day the plan reached the progress made, less the
  end of yesterday. Where the plan is flat (before the start, between phases, after the end) the
  nearest day counts, so a project waiting for its start isn't "ahead".
- **Health** of an active project: delayed once its revised end (planned end plus the days approved
  changes add) has passed, or when it is more than the larger of 7 days and 10% of its duration
  behind; at risk past the larger of 2 days and 3%, or when any phase is late (past its end, or 20
  points behind its own plan). A finished project (100%) is on track whatever the dates.
- Changing a project to active stamps its actual start with today, and to completed its actual end,
  unless the form gives the day: a project entered while it already runs keeps the day it really
  started (found in the walkthrough, where every seeded project otherwise started "today").
- The Projects timeline draws each project's plan, planned start to revised end, which progress and
  today are read against; a finished project's bar is the time it took. Mixing the actual start with the
  planned end drew a project that started after its planned end as a sliver.
- These thresholds are product choices, not industry standards: change them in one place and the
  pages, Home and the client's page follow.

## Site diary

- One entry per project and company-local day (unique index), never for a day still to come. A
  second entry for the same day answers `409 day_taken` with the existing entry's id as `detail`, and
  the page opens that one instead.
- An entry can move phases' progress. It records each phase's change (from → to) and keeps the
  first "from" when it is edited again, so the record says where the phase was before that day.
  Phases moved by an entry start on its day, and end on it when they reach 100%.
- `shared` puts the entry (and its photos) on the client's page; entries are internal by default.

## Photos

- The browser shrinks each photo before uploading it: 1600 px JPEG at 0.82 and a 480 px thumbnail
  at 0.72. The server only checks the JPEG signature and the sizes (4 MB, 512 KB) and never decodes
  an image, so no image library is needed in the server image. A photo small enough is its own
  thumbnail. At most 3 000 per project.
- Files live on the PDF volume beside the company's logo,
  `<PDF_STORAGE_PATH>/<slug>/building/<projectId>/`. **They are not in the Mongo backups**, and
  `restore-tenant.sh` restores a company's documents, not its files: backing up that volume is an
  open point ([ops-and-deploy.md](ops-and-deploy.md)). Deleting a project deletes its folder.
- A photo of the site is shared by default; one of an issue stays inside the team. The team's pages
  show photos through `apiBlob` (the token travels in a header, not the URL).

## Issues and change orders

- Issues keep a history of status changes, with an optional note each. `resolvedAt` and `closedAt`
  follow the status, and reopening clears them. One assignee: an employee or a supplier. Overdue means
  open past its due date.
- Change orders go draft → sent → approved or rejected; only a draft or a sent one can be edited. An
  approved addition's lines are measured like the contract's (line key `co:<changeId>:<index>`), an
  omission lowers the contract's value, and the days move the revised end. An approved change can go
  back to draft only while no measurement measured its lines (`change_measured`), and can't be
  deleted (`change_approved`).

## Measurements (autos de medição)

- A new measurement starts with every billable line (the contract's, then each approved addition's)
  at the quantities the previous one measured to date. Staff type the **quantities to date**; a
  quantity above the contracted one or below the previous measurement's is refused with the line's
  key as `detail`.
- A line's value to date is its whole total once fully done, else quantity × unit price to the cent,
  so rounding never bills more than the line. The period's value is to date minus the previous one;
  the retention percentage is stamped on each measurement when it is made.
- Only one draft at a time (`measurement_open`, its id as `detail`); only the latest can be reopened
  or deleted, and only before it is invoiced.
- **Invoicing** creates an ordinary CRM invoice through `InvoiceService`, for the project's client:
  one line per line billed this period ("Auto n.º 3 · Paredes", in the company's language,
  `BuildingCopy`), and the retention held as a negative line. The measurement is claimed (status
  `INVOICED`) before the invoice is written, so a double click can't bill it twice. Cancelling or
  deleting the invoice frees the measurement (`InvoicedWork`, below) to be invoiced again.
- **A quote is billed once** ([billing-and-pdfs.md](billing-and-pdfs.md)): the first measurement
  marks the project's quote (`crm.quotes.projectId`), after which the quote can't be invoiced or
  turned into a Serviços row (`already_measured`, also in the assistant and the agents' step). A quote
  invoiced or serviced first can't be measured (`quote_billed`). Deleting a project's last measurement
  clears the mark.
- The contract is locked once a measurement exists (`contract_locked`), except its lines'
  descriptions, and a project with measurements can't be deleted (`in_use`): its invoices point at it.

## The client's page

- `/app/share/{token}`, under `/app` because the production proxy forwards `/app*` as it is. The token
  is 32 random bytes (base64url) on the project, enabled or not; rotating it makes a new one and the
  old link stops working at once.
- It shows the company's name and logo, the project's progress, the updates the team posted, the plan
  moved by approved changes, and only the diary days and photos marked shared. Never money, issues,
  notes or people. A phase shows as late only on the team's side.
- Answers `404` for an unknown or disabled token and for a company that isn't active; sends
  `X-Robots-Tag: noindex, nofollow` and `Referrer-Policy: no-referrer`, and the data `no-store`.
- The page is plain scripts over the shared stylesheet and catalogs, in the company's language.

## Home

The Projects card counts active, planning, at-risk and delayed projects, open and overdue issues,
diaries written today and active projects without one, changes waiting for the client, and approved
measurements to invoice, with the four projects furthest behind. Its Needs-you rows are delayed
projects, overdue issues (both ranked after pending bookings), measurements to invoice and changes
waiting (after due-soon payments). Issues and changes count on a finished project too: snags after the
handover, an extra still to sign.

## Events, for the agents

- The services append domain events after their writes (`BuildingEvents`): `project.created`,
  `project.status_changed`, `site_log.created`, `site_issue.created` and `.status_changed`,
  `change_order.sent` and `.decided` (`via` the team or the client's link), `measurement.approved` and
  `.invoiced`. Each names the project and its client as related records, so the client's timeline shows
  it and an agent's WhatsApp, task or notice has the client.
- Health is computed, not stored, so `ProjectHealthWatch` (hourly, on a lease, like the agents'
  scheduler) keeps each running project's last-seen health in `watchedHealth`, written without a new
  version so it can't overwrite an edit, and appends `project.at_risk` or `project.delayed` when it
  turns so. Its first look only stores. Two events rather than one `health_changed`: the Agents page's
  status filter offers a subject's statuses, and a project's statuses aren't its health.
- `AgentContextBuilder` loads each Building subject (`BuildingSubjects`) with its project's figures and
  its client; the Agents catalog offers the events, statuses and variables per subject.

## The client approves a change from a link

- Sending a change gives it its own link, `/app/share/change/{token}` (32 random bytes, a partial
  unique index on `approval.token`). The client reads the change as sent and approves or rejects it in
  the name they type, with an optional note. JSON-only, `noindex`, `no-referrer`, `no-store`, only while
  the company is active.
- One answer, while the change is sent: the decision replaces the change only over the status and
  `updatedAt` it read, so a second answer, or one racing the team's, is `409 already_decided`. Editing a
  sent change takes it back to draft and drops the link, so the client never approves what they didn't
  read; any decision keeps the link so the client can see it.
- The decision records `decidedVia = CLIENT_LINK` and the typed name, appends `change_order.decided`
  with a `CLIENT` actor (a new `ActorType`), and rings every user's bell (`change_order_decided`).

## Releasing the retention

- Once a project is finished and all its measurements are invoiced, the Measurements tab offers one
  invoice to the client of the retention still held: the retention of its invoiced measurements less
  what was released (`MeasurementMath.retentionHeldCents`). Refused while a measurement is a draft or
  to invoice (`measurement_pending`): its retention would be held after the release.
- The project keeps the releases (`retentionReleases`), claimed through the versioned project write
  before the invoice exists, so a second click finds nothing held. Cancelling or deleting that invoice
  pulls the release (and bumps the version) through a second `InvoicedWork` hook.

## PDFs

- `BuildingPdf` writes the measurement (lines, totals, retention, net, signature boxes) and the diary of
  a period (at most 92 days, the six first photos of each day as thumbnails) over PDFBox, flowing from
  page to page with a running head, repeated column headings and page numbers. It is separate from the
  CRM's `PdfGenerator`, whose page the company designs block by block and which is Portuguese-only.
- Text, numbers and dates follow the company's language through `BuildingCopy` (`1 234,56 €`,
  `€1,234.56`, `1.234,56 €`). Photos and logos PDFBox can't read (a WebP) are left out, not fatal.

## Job costing

- A payment can name the project it was for (`crm.payments.projectId`, like `clientId`); a project has
  an optional cost budget, a labour cost per hour and the time clock's work sites of the job
  (`siteIds`, one project per site, `site_in_use` otherwise). The link sits on the project, not on the
  site, so Building reads the time clock and the time clock knows nothing of Building.
- `ProjectCostsService`: the bills (not cancelled) by payee, the hours clocked at its sites by employee
  (an open shift counts to now) at the project's rate, the budget used, and two margins. **Expected** is
  the current contract less the budget, or less the costs once they pass it; without a budget, less the
  costs so far. **To date** is what was measured less the costs.
- An employee's pay linked to the project and their clocked hours would count twice; the tab says so.

## Client updates

- The Client updates tab drafts an update for a period (the last 7 days by default) with the company's
  model, in its language, from what the client may read: the diary days shared with them, the phases
  and how they moved, the changes they approved. Never money, the site's problems or anyone's name.
  Within the monthly token budget (`409 token_budget`), counted as `building` usage; `502
  ai_unavailable` when the model doesn't answer; `nothing_to_report` when the period has nothing.
- The team edits and posts it; the client's page shows the posted updates, newest first. The code
  calls them reports (`building.reports`, `/reports`), because the client's page already calls the
  shared diary days updates.

## My sites

- An employee page, `my-sites`, when the company has employees and the diary or the issues: the running
  projects whose site manager the employee is. They write and edit the diary, add photos, and log and
  move issues through `/app/api/portal/sites…` and the team's services, stamped with their name. A
  project someone else runs isn't found; a write the company's modules lack is `page_unavailable`.
- The dashboard reuses the team's diary form (given the site, it posts to the portal), and the shared
  photo helpers read and upload through the portal on an employee's sign-in.

## Gotchas

- **Writes stamp time to the millisecond** (`Stamp`). MongoDB stores dates in milliseconds, and
  `MeasurementRepository.replace` only writes over the stored `updatedAt` it read; an instant with
  microseconds would never match the stored one.
- Project writes go through `ProjectRepository.update`, which retries on the `version` it read, so a
  diary entry moving a phase and someone editing the project at once don't lose either change.
- `InvoicedWork`: `crm/` can't import `building/` (the domains would form a cycle, see
  [`docs/plan-gradle-modules.md`](../docs/plan-gradle-modules.md)), so `InvoiceService` takes the work
  an invoice bills as a list of release hooks, and the dashboard's `TenantCrm` passes two: the
  measurements' and the retention releases'. Only the dashboard's Invoices routes cancel or delete
  invoices today; a new caller that does must pass the same hooks.
- **The PDF fonts are git-ignored** (`src/main/resources/pdf/*`, [gotchas.md](gotchas.md)). Without
  them both generators fall back to Helvetica, which covers Portuguese and Spanish text, `€`, `º` and
  `°`; a PDF test can't tell the two apart.
- **Building depends on the time clock, never the reverse:** costs read `timesheets` repositories, so
  nothing in `timesheets/` may import `building/` (hence the sites on the project).
- `dashboardApiTest` hands its `FakeModel` to tests (`model.answer`, `model.last()`), for a feature
  that calls the AI from a route.

## Not done yet

The mobile app's Building screens; costs by cost code and per-employee labour rates; the measurement
PDF's look under the company's document template. Phase 3: drawings with pins, checklists, offline
capture, BC3 import.
