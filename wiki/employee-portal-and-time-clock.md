---
updated: 2026-10-09
---

# Employee portal and time clock

What an employee's own sign-in can reach: registering services for the team to approve, and the opt-in time clock. Both run under `/app/api/portal/`.

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
- **Mobile has no employee page:** an employee signing in to [mobile](mobile.md) is signed out at
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
  `timesheets`) before `my-services` (with `services`), both need `employees`, and the session lands
  on the first. Employee routes are `/app/api/portal/time…` (status, punches, forgotten clock-out,
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
- Phase 2 (offline punches, Home tiles, agent triggers) is in the plan.

Merging it after #54 and #55: six files conflicted, all with additions on both sides that were kept
together (the catalogs' `time` block beside the column picker's `columns`, notification kinds, tones
and texts for the time clock and the handoff, `timesheetsDeps` beside `assistantDeps`, and both
scripts in `index.html`). **Gotcha for later edits:** `/app`'s `api()` throws #54's `apiError(res)`,
which since this merge also carries the parsed body as `err.body`. `timesheets.js` reads
`err.body.distanceM` and `siteName` to say how far from which work site a clock-in was refused. CI
deployed `ef78cbe`, health ok, 0 `ERROR` lines three and a half minutes after boot, past the retention
job's first scheduled run.
