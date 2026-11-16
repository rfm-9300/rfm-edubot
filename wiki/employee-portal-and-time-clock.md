---
updated: 2026-11-14
---

# Employee portal and time clock

What an employee's own sign-in can reach: registering services for the team to approve, and the opt-in time clock, with its hours per job and planned shifts (phase 2). Both run under `/app/api/portal/`.

## Employees sign in and register their services (PR #48, shipped 2026-10-03, `4f4b2cd`)

Built by a Cursor cloud agent. An employee (colaborador) can get their own `/app` sign-in and
register work they did for a client; the team approves it, and an approved submission is saved as an
ordinary Serviços row "done by" that employee, ready to invoice. `docs/architecture.md` has the flow.
Decisions that matter later:

- **A sign-in is a `dashboard_users` row with role `TENANT_EMPLOYEE`**, linked to the employee record
  (`employeeId`, partial unique index) and its company (`employeeTenantId`). Admins, and operators
  opening the dashboard, give, change, reset, disable or remove it from the employee record
  (`/app/api/crm/employees/{id}/access`). The backoffice's `POST /admin/api/tenants/{slug}/dashboard-users`
  answers `400 invalid_role` for that role, so the record is the only way to make one. Approving or
  rejecting is open to every team member (admins and members), because members can already add
  services directly; giving sign-ins is admin-only.
- **Locked down in three layers.** `DashboardAccessPolicy` opens only the employee record's company,
  only while that company has the `employees` and `services` modules and the record exists
  unarchived: disabling the sign-in, archiving or deleting the employee, or switching a module off
  ends the session on the next request, and password and Google sign-in refuse it too.
  `requireModule` is always false for an employee, so every module-gated route answers 403. And the
  `dashboard` JWT validator lets an employee's token reach only `/app/api/me`, `/app/api/account/*`
  and `/app/api/portal/*` (`allowsPath`; dot segments and `%2e` refused), which covers the routes that
  check no module (WhatsApp/Instagram connect, email, notifications, company switch). **Any route an
  employee needs must live under `/app/api/portal/`.**
- **Employee side:** one page, My services (Pending / Approved / Rejected chips, Register a service
  with the Serviços form, change or withdraw while pending, the rejection reason), plus the account
  drawer; no Home, bell or company modules. Clients come only as id, number and name for the picker.
- **Team side:** the employee record shows a To approve figure, each pending service under Needs
  attention, the App sign-in row and the registered services. A submission opens as a detail with
  Approve, Change and approve (same form; the submission keeps the approved version, marked
  adjusted) and Reject (optional reason). The employees list, the nav count, Home's Needs you (oldest
  five) and the bell all surface pending ones. An approved service shows who did it.
- **Data:** `crm.service_submissions` (indexes `tenantId+employeeId+createdAt` and
  `tenantId+status+createdAt`); `crm.client_services.employeeId` (optional, partial index) records who
  did the work. Approving claims the submission atomically before saving the service, so two
  approvals at once create one service; a failed save undoes the claim.
- An employee with registered or done services is archived instead of deleted (the history rule in
  "Deleting clients, suppliers and employees" on [crm-directories.md](crm-directories.md)); deleting one also deletes their sign-in. Agents no
  longer offer employee sign-ins as task assignees.
- **Mobile:** My services is a screen of its own since 2026-11-10 (register, change or withdraw
  while pending, the rejection reason), and the team approves or rejects from the employee record
  ([mobile](mobile.md)). Before the time clock, an employee signing in to the app was signed out at
  its first call outside `/me`.
- `create-mocks` seeds an employee sign-in (Ana Costa, `COL-001`) with two pending, one approved and
  one rejected service; the credentials are in `mocks/mongo/README.md`.

The agent reported 563 tests green (new `EmployeeWorkRoutesTest`, `ServiceSubmissionRepositoryTest`,
`DashboardAccessTest`). CI tested and deployed it (deploy job green); no separate production check.

## Employee time clock (PR #53, shipped 2026-10-08, `ef78cbe`)

A Cursor cloud agent's PR (branch `cursor/employee-time-clock-5df3`, opened 2026-10-07). Employees clock
in and out and take breaks from `/app` or the mobile app. The team sees who is working, corrects shifts
(a reason is required and kept as history), approves and exports hours. Plan and research (Código do
Trabalho art. 202.º, Lei 58/2019 art. 28.º, CNPD on geolocation; Connecteam, Jibble and others):
`docs/plan-time-clock.md` in the repo. Checked in the code when it merged:

- **Opt-in module `timesheets`,** like `agents`: a null module list doesn't include it, and turning it
  on also turns on `employees` (`DashboardModules`). A company gets it from the backoffice.
- **An employee's own sign-in now has pages.** `EmployeePortal.pages` lists `my-hours` (with
  `timesheets`) before `my-services` (with `services`) and `my-sites` (with the site diary or issues,
  added 2026-10-21: [building-projects.md](building-projects.md) → My sites), all need `employees`,
  and the session lands on the first. Employee routes are `/app/api/portal/time…` (status, punches, forgotten clock-out,
  notes, phone enrolment); team routes are `/app/api/timesheets…` (working now, shifts with totals and
  overtime, corrections, manual shifts, approvals, CSV, rules, work sites, phones).
- **Company rules,** with the defaults a new company gets: location `OPTIONAL` (off, optional,
  required), work sites `FLAG` (flag or block; only a clock-in is ever refused), biometrics `OPTIONAL`
  (off, optional, required), a 12-hour longest shift, an 8-hour day and a 40-hour week.
- **Storage:** `timesheets.shifts`, whose partial unique index `one_open_shift` allows one `OPEN`
  shift per employee, plus `timesheets.sites`, `timesheets.devices` and `timesheets.challenges` (TTL 5
  minutes). Writes are versioned. `TimesheetLocationRetention`, a lease-guarded job started 3 minutes
  after boot, removes coordinates after 90 days and keeps the site verdict.
- **Phone signatures:** one EC P-256 key per phone (Android Keystore behind `BiometricPrompt`,
  StrongBox where present; iOS Secure Enclave after Face ID or Touch ID), one-use nonces, and
  signatures over `nonce:ACTION`. A bad signature is refused, never counted as unverified. New bell
  kinds: `time_device_enrolled` and `time_missed_clock_out`.
- **Web:** My hours (clock card, breaks, forgotten clock-out, shifts by week) and Timesheets (working
  now, filters, detail with map links, correct/close/add with a reason, approve clean, CSV, sites,
  rules), plus Hours and Phone panels on the employee record; `app/timesheets.js`
  (`window.TimesheetsUI`).
- **Mobile:** an employee's sign-in lands on My hours, which gathers the evidence on the phone and stops
  early with a readable reason when the company's rules can't be met. Location and signing sit behind
  common interfaces (`LocationProvider`, `DeviceSigner`, `DeviceKeys`) with platform implementations,
  as [KMP engineering guide](kmp-engineering-guide.md) asks. **Never run on a device:** the agent's VM couldn't start an
  emulator. CI builds Android, links the iOS framework and runs the native tests, but permission
  prompts, fingerprint and Face ID, and re-enrolling after a biometric change need a real phone. Details
  on [mobile](mobile.md).
- Phase 2 (offline punches, Home tiles, agent triggers) is in the plan. Hours per job and schedules
  came first, as the next section says.

Merging it after #54 and #55: six files conflicted, all with additions on both sides that were kept
together (the catalogs' `time` block beside the column picker's `columns`, notification kinds, tones
and texts for the time clock and the handoff, `timesheetsDeps` beside `assistantDeps`, and both
scripts in `index.html`). **Gotcha for later edits:** `/app`'s `api()` throws #54's `apiError(res)`
(in `admin/common.js` since PR #84, shared with the backoffice), which since this merge also carries the
parsed body as `err.body`. `timesheets.js` reads
`err.body.distanceM` and `siteName` to say how far from which work site a clock-in was refused. CI
deployed `ef78cbe`, health ok, 0 `ERROR` lines three and a half minutes after boot, past the retention
job's first scheduled run.

## Hours per job and schedules (phase 2, built 2026-11-13)

Rodrigo asked for a "very complete" time clock for companies that need strict, specific and detailed
hours. Of four candidate areas he picked **hours per job** and **schedules and attendance**; sign-off
with locked periods (and a monthly record PDF) and absences with labour-rule checks were left for
later, as was the team's side on mobile. Plan: [`docs/plan-time-clock-jobs-schedules.md`](../docs/plan-time-clock-jobs-schedules.md);
as built: [`docs/architecture.md` → Time clock](../docs/architecture.md#time-clock). Both rules default
to off, so a company that changes nothing keeps phase 1. Decisions that matter later:

- **Segments live inside the shift**, not in their own collection. Splitting time is then one versioned
  write with the clock-in, switch or correction that causes it, approval covers it, and the shift's
  history holds the team's job changes (`ShiftEdit.jobs`, the times kept on both sides). Reports read
  the period's shifts and cut them (`WorkLog.spans`); Building's job costing queries
  `segments.job.projectId` (indexed).
- **Minutes are cumulative**: a segment holds `⌊W(end)/60⌋ − ⌊W(start)/60⌋` of the seconds worked, so the
  parts always add up to the shift's worked minutes, breaks counted in none.
- **A typed split goes in time order** (`WorkLog.inOrder`; the first part, the shift's start, stays first).
  Both editors add a new part at the end of the form, so refusing parts out of order meant splitting the
  middle of a shift took retyping every later part (found on a phone). Only two parts at the same minute
  are refused (`segments_same_start`).
- **A job keeps the names it had** (client, project, activity), so a rename or delete doesn't rewrite
  history; a segment is billable when its activity is, or, without one, when it names a client or
  project. Resolving a job accepts archived clients and activities out of use, so an old shift can still
  be edited; the pickers only list the live ones.
- **Strict means strict, but never stuck.** Under `workLog = REQUIRED` a clock-in or switch without a
  complete job is refused (`job_required`, `job_incomplete` naming the part as `detail`), untagged time
  flags `UNALLOCATED`, and approving refuses it (`409 unallocated`; a bulk approve skips such shifts and
  answers how many as `skipped`). Clock-out is never refused. An employee may save a split with untagged
  parts; the flag and the approval stop them from counting.
- **Projects come through a port.** `timesheets/` must not import `building/` (Building already reads
  the time clock for job costing), so `JobProjects` is defined in the time clock and Building implements
  it (`BuildingJobProjects`), handed to the routes in `AppRoutes`.
- **A switch is a punch** (`SWITCH`): it gathers location and the phone's signature like any other, a
  geofence only flags it, and one within a minute of the segment's start changes that segment's job (a
  wrong pick fixed at once) instead of cutting a new one. Switching to the job already running is `409
  same_job`.
- **Matching a clock-in to a plan** looks from four hours before the planned start (only
  `earlyClockInMinutes` under the strict rule) to its end, nearest start first; a team-added shift is
  matched the same way. Clocking out for lunch and back in links both shifts to one plan:
  `PlanLinks.relink` keeps lateness on the first and early leave on the last, so neither reads as late
  or as leaving early.
- **The clock card's plan** (`status.planned`) is the open shift's plan, else one matching now, else one
  later today; never a later day's (those are `upcoming`). So a plan dated another day began on an
  earlier one (a night shift past midnight, a clock-out forgotten overnight): web and phone name it by
  its day (`plannedOn`), not as the next one, which is how a forgotten Friday shift read on Saturday.
- **No-shows** are found by `AttendanceWatch`, not computed at read time only, because the team should
  hear about one while it matters: once per plan (`claimAlert`), not for employees who left, and not for
  plans older than a day (an outage shouldn't ring old news). A plan found attended (late, or by a shift
  that ran over it) is marked so it isn't looked at again.
- An attended plan can't be deleted (`planned_attended`), only re-timed, which moves its shifts' lateness
  with it; copying a week skips whatever would overlap and never copies an excuse.
- **Web:** the jobs and schedule code sit in two self-contained scripts beside `timesheets.js`
  (`time-jobs.js`, `time-schedule.js`), which its `init()` mounts with a kit of its helpers. By job and
  Activities only show while the company logs jobs. The planner reuses the bookings board's
  `.week-event` cards in a `.tbl--stack` table (`.planner`, `table-layout: fixed`, so long attendance
  text ellipsizes instead of widening the week).
- **Mobile** (employee side only): [mobile.md](mobile.md#hours-per-job-and-schedules-2026-11-13).
- **Checked:** 1,038 backend tests green (new `WorkLogTest`, `AttendanceTest`,
  `TimeClockJobsScheduleRoutesTest`, a Building costs case), and a browser walkthrough of every new tab,
  drawer and form against a throwaway local stack (pt-PT, light and dark, 390 px), which caught a
  shadowed helper printing `[object Object]` in the work log and a planner that scrolled sideways. The
  employee's side then ran on a real Android phone ([mobile.md](mobile.md#hours-per-job-and-schedules-2026-11-13)).
