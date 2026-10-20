---
updated: 2026-10-20
---

# Building: projects and their progress

The opt-in category for construction and renovation companies: **Projects**, **Site diary**,
**Issues**, **Change orders** and **Measurements**, a Projects card on Home, and a read-only page the
client opens from a link. Built in PR #88 (2026-10-20). The plan, with the market research behind it
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
  `MeasurementService`), and imports nothing from Ktor's server (`ArchitectureBoundariesTest`).
  `building/api/` has the requests, DTOs and views; `dashboard/building/` the five features and the
  client's page; Home's card is `dashboard/overview/ProjectsOverview.kt`.
- Frontend: `building.js` (what the pages share: labels and pills, the progress bar and the timeline,
  the project picker, shrinking, uploading and viewing photos, the nav counts), `projects.js`,
  `site-diary.js`, `site-issues.js`, `change-orders.js`,
  `measurements.js` ([dashboard-frontend.md](dashboard-frontend.md)); the client's page is
  `share/index.html` + `share/share.js`. The components are in
  [`design-system/components.md`](../design-system/components.md) → Building.
- Collections, all in the data database with `tenantId`: `building.projects`, `building.site_logs`,
  `building.photos`, `building.issues`, `building.change_orders`, `building.measurements`. Numbers come
  from `crm.sequences`: projects `OB-001`, issues `OC-001`, change orders `TM-001`; measurements are
  numbered per project.

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
- Changing a project to active stamps its actual start with today, and to completed its actual end.
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
- It shows the company's name and logo, the project's progress, the plan moved by approved changes,
  and only the diary days and photos marked shared. Never money, issues, notes or people. A phase shows
  as late only on the team's side.
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

## Gotchas

- **Writes stamp time to the millisecond** (`Stamp`). MongoDB stores dates in milliseconds, and
  `MeasurementRepository.replace` only writes over the stored `updatedAt` it read; an instant with
  microseconds would never match the stored one.
- Project writes go through `ProjectRepository.update`, which retries on the `version` it read, so a
  diary entry moving a phase and someone editing the project at once don't lose either change.
- `InvoicedWork`: `crm/` can't import `building/` (the domains would form a cycle, see
  [`docs/plan-gradle-modules.md`](../docs/plan-gradle-modules.md)), so `InvoiceService` takes the work
  an invoice bills as a list of release hooks, and the dashboard's `TenantCrm` passes the measurements'.
  Only the dashboard's Invoices routes cancel or delete invoices today; a new caller that does must
  pass the same hook.

## Not done yet

From the plan's phase 2: a foreman's "My sites" in the employee portal, job costing (payments and
hours against the project), the client approving a change order from the link, an AI weekly update
from the diary, releasing the retention at handover, PDFs of a measurement and of the diary, domain
events for the agents, and the mobile app. Phase 3: drawings with pins, checklists, offline capture,
BC3 import.
