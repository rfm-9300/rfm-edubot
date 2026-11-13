---
updated: 2026-11-10
---

# Mobile app (Kotlin Multiplatform)

`mobile/` in this repo, GitNexus index name `rfm-edubot-mobile`. A Kotlin Multiplatform + Compose Multiplatform companion app for the Ktor server ([overview](overview.md)). Binding engineering guide: [KMP engineering guide](kmp-engineering-guide.md); treat it as an instruction, not a suggestion.

## Shape (as of 2026-08-17)

Matches the 2026 KMP default the guide describes: `shared/` library, `androidApp/` /
`iosApp/` thin shells, `build-logic/` convention plugins, feature modules
(`feature/assistant`, `inbox`, `crm`, `contacts`, `auth`, `overview`, `persona`, `settings`)
and `core/` libraries (`ui`, `network`, `model`, `localization`, `common`, `testing`).

The web [design system](../design-system/AGENTS.md) **does not apply** here: its `AGENTS.md` says
mobile is KMP and out of scope for that stylesheet.

## Behind the backend; overhaul merged in PR #44 (2026-10-05, `977c2d3`)

The app has fallen behind the server. Per a Cursor cloud agent's review (2026-10-02) it was about
3.1k lines against a 33k-line server and modelled 8 of the 17 tenant modules. Its create-client has
failed since `b6448f9` made a NIF mandatory (`400 tax_id_required`; see [crm-directories.md](crm-directories.md)), and
employees with their own sign-in (`TENANT_EMPLOYEE`, `4f4b2cd`) had no page here, so they were signed
out at the first call outside `/me` (until the time clock, PR #53, below).

That agent's draft PR #44, "Bring the KMP mobile app back up to the backend it talks to" (about
+9.9k / −1.7k lines, 103 files), re-platforms it. Per the PR:

- a new `core:data` module of repositories, which feature modules depend on instead of `core:network`;
  one API interface per dashboard area replaces the 23-method interface;
- `DashboardHttpClient` owns the bearer token (screens no longer pass it) and maps statuses to a typed
  `AppError`; a 400 keeps the backend's error code, and a 403 stays distinct from a 401 so a missing
  module never signs anyone out;
- `CachedResource` serves the last good response from disk and keeps it on a failed refresh;
- screens for agents, bookings, jobs, payments, suppliers, employees and notifications, and inbox
  parity with the web (polling `/updates?since=`, needs-reply filter, AI switch, 24-hour window);
- a light theme whose tokens copy the web's `minimal` skin, compile-checked string keys (`Txt`) with
  en/pt/es catalogs and parity tests, and Back navigation that no longer closes the app;
- a JVM target so pure-Kotlin modules test without Android or iOS, and Mobile CI running every
  module's tests (139 tests, up from 3, per the agent).

**Blocked on 2026-10-03, merged on 2026-10-05 (`977c2d3`).** The repo root `.gitignore`'s unanchored
`data/` rule had kept `mobile/core/data/` out of every commit, so all three Mobile CI jobs failed at
configuration (`:core:data` had no directory). The merge anchors the rule as `/data/`. "Shape" above
predates the overhaul: recheck the new structure against [KMP engineering guide](kmp-engineering-guide.md) before relying
on it.

Still web-only after #44: the agent builder, the document-template studio, the widget customiser,
WhatsApp template management, Instagram, the Google/Gmail integrations and persona file uploads.
Google sign-in, push notifications and inbox media are not started (`docs/plan-kmp-mobile-frontend.md`
on the PR branch).

**The client form didn't follow per-company client fields (2026-10-06, fixed 2026-11-10, below).** Since PR #52
(`a1e6a74`, see [crm-directories.md](crm-directories.md)) each company picks which client fields staff must fill and can add
its own. The form #44 added still requires NIF and address on its own (`ClientFormViewModel.canSave`)
and has no contact person or custom-field inputs. So it blocks saves a company has made optional, and
it gets `400 contact_person_required` or `custom_field_required` when a company requires those.
Responses now carry `customFields`, which the app's `ignoreUnknownKeys` drops harmlessly. The fix is
to build the form from `GET /app/api/crm/clients/fields` (`standard` keys with `required`/`locked`,
`custom` field definitions).

## Time clock, Persona and assistant changes (merged 2026-10-08)

Three cloud-agent PRs merged that day touch the app (server side: [employee-portal-and-time-clock.md](employee-portal-and-time-clock.md),
[ai-persona-and-bot.md](ai-persona-and-bot.md), [ai-assistant.md](ai-assistant.md)):

- **Time clock (PR #53, `feature/timeclock`).** An employee's own sign-in now lands on My hours, the
  first page the server lists for that session, so `TENANT_EMPLOYEE` users have a screen. Employee
  sessions never call company endpoints (no Home, bell or company settings), and the signed-in
  screens' view models are cleared when a different session starts, because an admin's leftover
  pollers would otherwise sign out the next employee on a shared phone. A punch carries one location
  fix and, when the company asks, a signature from a key only the phone's biometrics unlock. Location
  and signing are common interfaces (`LocationProvider`, `DeviceSigner`, `DeviceKeys` in
  `core/common`) with `androidApp` and `shared/iosMain` implementations (Android `LocationManager`
  and a Keystore key behind `BiometricPrompt`, StrongBox where present; iOS Core Location and a
  Secure Enclave key after Face ID or Touch ID, with changed biometrics detected). The agent ran 260
  mobile tests and CI builds Android, links the iOS framework and runs the native tests, but **it has
  never run on a device**: the guide's "exercised once in a running app" is still open (permission
  prompts, fingerprint and Face ID, re-enrolling after a biometric change).
- **Persona (PR #54).** Members can no longer change the persona (`403`), but the app's Persona screen
  still offers them editing; it could hide it with the response's new `canEdit`. DTO changes are
  additive and the app ignores unknown keys.
- **AI Assistant (PR #55).** The backend sends a card's `preview` as a JSON object and the app expected
  a string, so cards with a preview couldn't be read; `core/model` and `feature/assistant` now read the
  object and show it. Assistant settings, rename/delete and retry are web-only.

## Agent notes

- This folder is a valid workspace of its own (`AGENTS.md` / `CLAUDE.md` also carry a GitNexus
  block, pinned with `<!-- gitnexus:keep -->` and without the mandatory impact / `detect_changes`
  rules since 2026-09-29; see [gotchas.md](gotchas.md)). Consult this wiki (`wiki/index.md` at the
  repo root) even if the root `AGENTS.md` was not read.
- Quality gate and structure defaults live on [KMP engineering guide](kmp-engineering-guide.md), not in this page.
  When the app's actual stack diverges from the guide, update **both** this page and the
  guide's "where this binds" note — do not silently fork.

## Catching up with the web (2026-11-10)

The app hadn't changed since 2026-10-08 while the web gained about 150 commits. Rodrigo asked to bring
it up to date; this pass covers the CRM, the employee pages and the Building category. Email stays
web-only.

- **Fixed what was broken.** The client form is built from `GET /crm/clients/fields`: the standard
  fields in the company's order, each optional unless it requires it, then its own fields by type
  (text, number, date, choice as chips, yes/no as a switch). Every shown field is sent, an empty one
  as `""`, because a PATCH keeps an omitted field and the backend clears an address that isn't sent.
  A `custom_field_*` refusal marks the field its `detail` names. Persona reads `canEdit` and is
  read-only for members. A cancelled invoice or payment never offers Mark paid.
- **Records, as on the web.** Each CRM list row opens a record. The **client** record shows its
  profile with the company's own fields, a billed / to receive / overdue strip, and its invoices,
  quotes and services. A **quote** record has its status steps, Turn into invoice (asks for a due
  date), Turn into a service, and Delete, offered only while the quote isn't billed. An **invoice**
  record has received and to-receive amounts, installments to mark received, ATCUD, project or
  measurement, Cancel and Delete. A **service** record shows who did it (and changes that), Cancel or
  Reopen, and Delete. The **employee** record has a month of services with previous and next, the
  services they registered for the team to approve or reject, and payments. The **supplier** record
  shows the point of contact, the services they do and payments. A **catalog** row opens the item to
  edit or delete, with category chips from `/crm/catalog-categories`. New service is a form with
  lines, a day and who did it.
- **Employee pages.** `my-services` and `my-sites` are screens now, so an employee whose company
  has no time clock no longer lands on Settings. My services lists what they registered by status;
  they register one with the same form the team uses (`ServiceFormViewModel` takes where the options
  come from and where the draft goes), and change or withdraw it while pending. My sites lists the
  projects they run and whether today's diary is written; a site shows phases, the last two weeks of
  diary and the issues, and they write the diary and log and move issues.
- **Building** (`feature:building`, under its own nav group): Projects (progress against the plan,
  health, phases whose progress can be set from site, contract / measured / to invoice / retention,
  and the project's diary, open issues, changes and measurements), Site diary (write and edit, with
  weather, people, phase progress and shared), Issues (log and move with a note, history), Change
  orders (send, approve or reject for the client, back to draft), Measurements (approve, reopen,
  invoice). The diary and issue forms write through `SiteWriter`, which both `BuildingRepository`
  and `MySitesRepository` implement, so the team and a site manager share them.
- **Home** shows the Services and Projects cards (honouring `hiddenCards`), opens a project from
  "furthest behind" and from a delayed project in Needs you, and hides amounts behind Show amounts
  like the web, remembered until sign-out (which clears the cache).

**Still web-only:** photos (counts only; no image loading in the app yet), every PDF (no platform
file opener yet), creating projects, change orders and measurements, measuring quantities, editing
client fields, the Email page, Instagram, chat flows and the rest listed in
[`docs/architecture.md`](../docs/architecture.md#not-on-mobile). This pass was checked with the
guide's builds and tests (JVM, Android unit, iOS simulator, the iOS framework link, the debug APK),
decoding tests over server-shaped JSON, and on 2026-11-11 **a walkthrough on an Android phone**
against the local backend (`installDebug -PapiBaseUrl=http://localhost:8080` with
`adb reverse tcp:8080 tcp:8080`): Home, a project (phase progress, diary), an issue, a change sent
and approved, a measurement invoiced, invoice and client records, the client form, an employee's
approval, a catalog item, a supplier, and an employee's sign-in on My sites and My services. iOS
hasn't been run on a device.

**Gotchas from this pass:**

- Building statuses read differently per record (a resolved issue is "To check", an approved
  measurement "To invoice"), so the app uses the web's vocabularies (`building.issueStatus.*`,
  `building.measurementStatus.*`…) through `Strings.label(prefix, value)`, not the generic `status.*`.
- New copy lives in `FeatureCatalogs.kt` with the three languages side by side, merged into the base
  catalogs; a test fails if a new key overrides an existing one.
- A diary edit names in `clear` only numbers the entry had and the person erased; a JSON null can't
  clear one, and the app's `Json` leaves nulls out (`explicitNulls = false`).
- **A form's view model needs a key per opening** (`rememberFormKey`). The signed-in view models
  live in `SessionViewModels` for the whole session, so a fixed key ("client-form:new") handed the
  second New client the first one's view model, already `saved`, and the form closed itself; #44's
  forms had this. Records clear their `gone` / `archived` flag as they step back (`consumeGone`).
- **What the phone walkthrough found** (all fixed the same day): three amounts side by side cut each
  to "€"/"€ 5.366,…" (`MoneyStrip` lays them two to a row now); Home's due dates printed raw, since
  `listStamp` only formats instants (`TenantClock.day` reads a plain day); a quantity or price printed
  as Kotlin prints a Double (`2.0`, `15.0`); "1 serviços" (a plural key now); and a pending service's
  generated name stayed fixed when its line changed (the form leaves a generated name empty).
- Kotlin/Native refuses commas in backtick test names; `scripts/check-native-test-names.sh` catches it
  before the iOS job does.

