# Proposal — Gradle modules per domain

Status: **Proposal, not started.** Nothing in the build changes until Rodrigo decides · Last updated: 2026-10-19

Whether to split the server's single Gradle project into one module per domain, so the compiler
enforces the boundaries that `ArchitectureBoundariesTest` checks today. Written alongside PR #87, the
structural refactor that split the dashboard API by area and put CRM writes behind services; that PR
does not touch the build.

---

## Recommendation

Don't split yet. Keep the boundaries as test-time rules, cut the package cycles listed below one
small PR at a time, and add a rule to `ArchitectureBoundariesTest` as each cycle goes. Revisit the
split when the cycles are gone and something makes it pay: a slow build, a second team, or a domain
another service wants to reuse.

## What a split would buy

- Boundaries the compiler enforces: a module can't import what it doesn't depend on, so `crm` couldn't
  reach into `dashboard` by accident.
- Incremental builds and test runs per module. Today one change recompiles the whole server, and the
  full suite (893 tests, Testcontainers Mongo) takes about two minutes on a cloud VM.
- `internal` that means "this domain's", not "anywhere in the server".

## Why it isn't safe today

Gradle modules can't depend on each other in a cycle, and the packages do. Counted from the imports on
2026-10-19 (`com.rfm.edubot.<package>` imports between top-level packages):

| Cycle | What closes it |
|---|---|
| `crm` ↔ `dashboard` | `crm/CrmTools.kt` imports `DashboardModules`; 25 files outside `dashboard/` import it. The module catalog is tenant configuration, not dashboard code |
| `crm` ↔ `events` | Repositories append domain events; `events/` reads quotes, invoices, payments and clients to describe them |
| `crm` ↔ `tenant` | `TenantPipelineFactory` builds the bot's `CrmTools`; `crm` reads `Tenant` |
| `agents` ↔ `integrations` | Gmail sync and the Email page use the agents' run, approval and outbound-log stores; agents send email |
| `agents` ↔ `notifications` | Notification routes reuse agents' DTOs and `canManageAgents`; agents raise notifications |
| `agents` ↔ `dashboard` | Home's agents card reads agents' stores and the assistant uses `UntrustedContent`; agents' routes use the module gate and `DashboardContext` |
| `bookings` ↔ `crm`, `bookings` → `dashboard` | Bookings link clients and bill Serviços; `BookingRoutes` is a dashboard feature |

Other costs, smaller but real:

- **`internal` breaks across modules.** Many types are `internal` and used from several packages
  (`crm/api` DTOs from the dashboard and the backoffice, `InboxService` from the assistant). In
  separate modules they would have to become public or move.
- **Test helpers are shared.** `testing/` (`TestMongo`, `FakeModel`, the `DashboardApi` HTTP harness)
  would become a test-fixtures module that every module's tests depend on.
- **The build and the image.** `io.ktor.plugin`'s `buildFatJar` builds `build/libs/app.jar` from one
  project; the Dockerfile, `ci.yml` and `release.yml` call it. The app module would have to keep
  producing the same jar at the same path.
- **Every import moves.** Moving a package to another module touches most files that use it, which
  conflicts with every open branch.

## If it goes ahead

Each step is one PR that keeps the build green, the jar's behavior identical and the suite passing,
with `DashboardRouteAuditTest` and `ArchitectureBoundariesTest` as the guard.

1. **Cut the cycles inside the one project.** Move `DashboardModules` and `EmployeeLookup` out of
   `dashboard/` (to `tenant/`); give domain events their own payload types so `events/` stops reading
   CRM models; move what `integrations/` and `notifications/` borrow from `agents/` (the run,
   approval and outbound-log stores, the notification DTOs and `canManageAgents`) to a package both
   can depend on. Add a boundary rule for each.
2. **`:core`** — `shared/`, `http/`, `config/`, `persistence/`, `tenant/model`, the module catalog,
   `events/`, `channel/` interfaces.
3. **`:crm`** — `crm/` (models, repositories, services, `CrmTools`, PDFs) and `bookings/`, on `:core`.
4. **`:messaging`** — `webhook/`, `messaging/`, `conversation/`, `ai/`, `whatsapp/`, `instagram/`,
   `web/`, `flows/`, `persona/`, `ratelimit/`, on `:core` and `:crm`.
5. **`:agents` and `:integrations`** — on the modules above.
6. **`:app`** — the Ktor application: `AppRoutes.kt`, `dashboard/`, `admin/`, `plugins/`, the other
   route files and `Application.kt`. It alone applies `io.ktor.plugin` and builds `app.jar`.

Steps 2 to 6 are mechanical once step 1 is done; step 1 is where the design work is.
