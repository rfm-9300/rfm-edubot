---
updated: 2026-10-09
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

**The client form doesn't follow per-company client fields (as of 2026-10-06).** Since PR #52
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
