# Implementation Plan — Time clock, phase 2: hours per job, schedules and attendance

Status: **implemented** (server, `/app` employee and team pages, the mobile app's employee side); the
as-built reference is [Time clock](architecture.md#time-clock) in the architecture doc, and the
decisions are in [the wiki](../wiki/employee-portal-and-time-clock.md#hours-per-job-and-schedules-phase-2-built-2026-11-13)
· Owner: Rodrigo · Last updated: 2026-11-13

Phase 1 ([plan-time-clock.md](plan-time-clock.md), shipped in PR #53) records attendance: clock in and
out, breaks, where and with which phone. This phase is for companies that need strict, specific and
detailed hours: **what** each block of time was for (client, Building project, activity, notes), and
**whether people kept to the plan** (planned shifts, late arrivals, early leaves, no-shows). Rodrigo
picked these two of four candidate areas on 2026-11-13; sign-off with locked periods and
absences with labour-rule checks are not in this phase. Mobile covers the employee's side; the team
works on the web.

Everything stays inside the opt-in `timesheets` module and its rules. A company that changes nothing
keeps phase 1's behaviour: both new policies default to off.

---

## 1. Hours per job

### What the best-known apps do

| App | How time gets a job |
|---|---|
| Connecteam | Clock in to a job and sub-job; "switch job" while clocked in; required job selection per time clock |
| Jibble | Activity and project chosen at clock-in; switch activity while clocked in; required fields per company |
| QuickBooks Time | Customer/job and service item at clock-in; switch; notes required by company setting |
| Harvest, Clockify | Time entries per project and task with notes; billable per task |

What we take: a job chosen at clock-in and switched while working, so the record is made live; editing
the split afterwards while nobody approved the shift; a strict mode where every worked minute needs a
complete job; billable hours by activity; reports by client, project, activity and employee.

### Concepts

- **Job**: a client, a Building project (it implies its client), an activity (a company-defined task
  code) and a note. Every part is optional on its own; the company decides which ones a job needs.
- **Segment**: a shift's working time is split into consecutive segments. Segment *i* runs from its
  `startAt` to the next segment's (the last one to the shift's end, or now while open), and carries a
  job or none. Breaks inside a segment don't count, so the segments' minutes always add up to the
  shift's worked minutes. A segment keeps snapshots of the names it pointed at (client, project,
  activity), whether it is billable, where it came from (`CLOCK`: a clock-in or switch; `EMPLOYEE`:
  set by the employee afterwards; `TEAM`: corrected by the team) and the site its punch was inside.
- A segment's minutes are cumulative: `floor(W(end)/60) − floor(W(start)/60)`, where `W(t)` is the
  seconds worked from the shift's start up to `t`. The sum telescopes to the shift's worked minutes.
- **Activity** (`timesheets.activities`): name, optional code, billable by default or not, in use or
  not. Admins manage them. A segment is billable when its activity is, and without an activity when it
  has a client or a project.

### Rules (`timesheets.settings`)

- `workLog`: `OFF` (phase 1: no jobs) · `OPTIONAL` (people may tag their time) · `REQUIRED` (every
  worked minute needs a complete job).
- `jobNeeds`: which parts a complete job has: `client`, `project`, `activity`, `note`. Under
  `REQUIRED` with none ticked, a job needs at least a client, a project or an activity.
- Under `REQUIRED`:
  - a clock-in or switch without a complete job is refused (`400 job_required`, or `job_incomplete`
    with the missing part as `detail`): the apps always ask first, so this only stops misuse;
  - a shift with time that has no complete job gets the flag `UNALLOCATED`;
  - approving such a shift is refused (`409 unallocated`), and approving in bulk skips it. Clock-out is
    never refused.

### Flows

- **Clock in** with a job (`job` on the `IN` punch). The planned shift's job, if any, is the default.
- **Switch** (`SWITCH` punch while working, not on a break): ends the current segment now and starts
  one with the new job. Location and the phone's signature are gathered as for any punch (a switch is
  evidence too: arriving at the next client's house); geofences only flag it. Switching to the job
  already running answers `409 same_job`; switching within a minute of the segment's start replaces
  that segment's job (a wrong pick fixed at once).
- **Edit the split afterwards** (`PUT …/shifts/{id}/segments`): the whole list, each segment a start
  (`HH:mm` on the company's clock; the first is the shift's start) and a job. The parts are taken in time
  order (the first stays first), so a form can add a part at the end for any time in the shift; two parts
  may not start at the same minute (`segments_same_start`), and every start falls inside the shift (before
  now while open). A start typed as the `HH:mm` of an existing boundary
  keeps that boundary's exact instant. The employee edits their own shift until it is approved; the
  team edits any closed or open shift with a reason, which is kept in the shift's history (jobs before
  and after) and sends an approved shift back for approval.
- Correcting a shift's times fits the segments to them: a later start trims or drops the first
  segments, an earlier end drops the ones that would start after it, an earlier start stretches the
  first.
- **Reports**: the team's *By job* tab totals a week or a month by client, project, activity or
  employee (hours, billable hours, time not accounted for) and lists the segments; a CSV exports the
  work log (one row per segment).
- **Building job costing**: a project's labour becomes the segments tagged with the project; a shift
  with no project tags still counts whole when it was clocked at one of the project's sites (phase
  1's rule), so nothing counts twice.

## 2. Schedules and attendance

### Concepts

- **Planned shift** (`timesheets.planned`): an employee, a day, a start and end (`HH:mm`; an end before
  the start is the next day), optionally a work site, a job and a note. One employee's planned shifts
  can't overlap. Excusing one (with a reason) takes it out of the absences.
- **Matching**: a clock-in at `t` is linked to the employee's planned shift whose window contains `t`:
  from `start − 4 h` (strict mode: `start − earlyClockInMinutes`) to its end, the nearest start first.
  The shift keeps a snapshot of the planned times. Someone who clocks out and back in within the same
  planned shift links the second shift too; only the first is checked for lateness, and only the last
  for an early leave. A shift the team adds is matched the same way.
- **Attendance** of a planned shift, computed when read: `EXCUSED`; `WORKING` or `ATTENDED` when a
  linked or overlapping shift exists (with how late and how early); otherwise `UPCOMING` before its
  start, `DUE` until the no-show time, `MISSING` until its end, `ABSENT` after.

### Rules

- `schedule`: `OFF` (planning only, nothing checked) · `FLAG` (flags and alerts) · `BLOCK` (also
  refuses a clock-in more than `earlyClockInMinutes` before the planned start, `409 too_early` with the
  time it opens, and one with no planned shift, `409 not_scheduled`).
- `lateGraceMinutes` (5), `earlyLeaveGraceMinutes` (5), `earlyClockInMinutes` (15), `noShowMinutes`
  (30).
- Flags under `FLAG` and `BLOCK`: `LATE`, `LEFT_EARLY`, `UNSCHEDULED` (a clocked shift with no planned
  shift; not for the team's manual shifts).
- **No-shows**: `AttendanceWatch`, every 5 minutes on a scheduler lease, finds planned shifts started
  `noShowMinutes` ago with no linked or overlapping shift, not excused and not alerted, and tells the
  team on the bell (`time_no_show`, `ref` `planned:ID`). Each planned shift alerts once.

### Flows

- **Team, Schedule tab**: a week planner (employees × days) with each planned shift's attendance; add
  one for several weekdays at once, edit, delete (not once attended: `409 planned_attended`), copy the
  previous week, excuse an absence. "Working now" also lists who is due or missing.
- **Employee, My hours**: today's planned shift on the clock (late by how much, when clock-in opens
  under the strict rule) and the next two weeks.

## 3. API

Employee (`/app/api/portal/time…`, page `my-hours`):

| Method | Path | |
|---|---|---|
| `GET` | `/app/api/portal/time` | Adds the policy's `workLog`, `jobNeeds`, `schedule`; the `planned` shift for now, `upcoming` ones, `clockIn` (`allowed`, `too_early` with `opensAt`, `not_scheduled`) |
| `GET` | `/app/api/portal/time/jobs` | What a job can name: clients, running projects, activities in use |
| `POST` | `/app/api/portal/time/punches` | `job` on `IN` and `SWITCH`; new type `SWITCH` |
| `PUT` | `/app/api/portal/time/shifts/{id}/segments` | `{segments: [{start, job}]}` on their own unapproved shift |
| `GET` | `/app/api/portal/time/schedule?from=&to=` | Their planned shifts (default: today and the next 13 days) |

Team (`/app/api/timesheets…`):

| Method | Path | |
|---|---|---|
| `PUT` | `/app/api/timesheets/shifts/{id}/segments` | `{segments, reason}` |
| `GET` | `/app/api/timesheets/job-options` | Same as the employee's, for the team's pickers |
| `GET` | `/app/api/timesheets/jobs?from=&to=&employeeId=&clientId=&projectId=&activityId=` | Totals by client, project, activity, employee, and the segments |
| `GET` | `/app/api/timesheets/jobs.csv?…` | The work log |
| `GET`/`POST` | `/app/api/timesheets/activities` · `PATCH`/`DELETE …/{id}` | Activities (writes: admins) |
| `GET` | `/app/api/timesheets/schedule?from=&to=&employeeId=` | Planned shifts with attendance |
| `POST` | `/app/api/timesheets/schedule` | `{employeeId, days[], start, end, siteId?, job?, note?}`, one per day |
| `PATCH`/`DELETE` | `/app/api/timesheets/schedule/{id}` | |
| `POST` | `/app/api/timesheets/schedule/copy` | `{from, to}`: Mondays of the weeks |
| `POST`/`DELETE` | `/app/api/timesheets/schedule/{id}/excuse` | `{reason}` |

`GET /app/api/timesheets` adds planned minutes, late arrivals and absences to the totals; the board
adds who is due or missing.

## 4. Data

| Collection | Change |
|---|---|
| `timesheets.shifts` | `segments[]`, `planned` (link and snapshot), `lateMinutes`, `earlyLeaveMinutes`, new flags; punches may carry the `job` they chose; edits may carry jobs before and after. Indexes: `tenantId+planned.id`, `tenantId+segments.job.clientId`, `tenantId+segments.job.projectId` |
| `timesheets.activities` | New, `tenantId+name` |
| `timesheets.planned` | New, `tenantId+day`, `tenantId+employeeId+day`, `alertedAt+startAt` |
| `timesheets.settings` | The new rules |

All in the data database, scoped by `tenantId` ([mongo-databases.md](../wiki/mongo-databases.md)).

`timesheets/` must not import `building/` (Building reads the time clock): the projects a job can
name come through a `JobProjects` port that Building implements and `AppRoutes` passes in.

## 5. Mobile (employee side)

My hours gains the job picker before clocking in (defaulting to the planned shift's job), a Switch job
button while working, today's planned shift and lateness on the clock, the next planned shifts, and a
shift detail with its work log, editable until approved (change a segment's job, split at a time,
remove a split). Same evidence flow as phase 1 for the `SWITCH` punch.

## 6. Not in this phase

Locked periods and employee sign-off with a monthly record PDF; absences and leave; break, rest and
night-work rules; billing approved billable hours as Serviços rows; per-employee cost rates; the
team's side on mobile; offline punches; kiosk mode.
