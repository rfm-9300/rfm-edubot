---
updated: 2026-10-20
---

# Dashboard API structure

How the server's `/app/api` is put together: one route file per area behind a registry, the module
gate, routes on top of services on top of repositories, the error contract, and the HTTP tests that
hold it in place. Who may sign in, and how a session becomes a `DashboardContext`, is in
[auth-and-access.md](auth-and-access.md). What each module unlocks is in
[`docs/architecture.md` → Tenant Module Access](../docs/architecture.md#tenant-module-access).
Introduced by PR #87 (2026-10-19), a structural refactor that changed no endpoint except the error
answers listed under [The error contract](#the-error-contract).

## One file per area, behind a registry

`dashboard/DashboardRoutes.kt` used to hold about 80 endpoints and was touched by almost every
feature. It now mounts `/app/api/me` and `installDashboardFeatures(deps)`. Each area is a
`DashboardFeature` object, its module(s) plus `Route.routes(deps)`, listed in `DashboardFeatures.all`
(`dashboard/DashboardFeature.kt`). The areas are Home (`OverviewRoutes`), `dashboard/inbox/`
(Conversations and Contacts), `dashboard/settings/` (Settings and the document studio),
`dashboard/crm/` (clients, catalog, quotes, invoices, services, suppliers, employees, payments),
`dashboard/building/` (projects, site diary, issues, change orders, measurements:
[building-projects.md](building-projects.md)), persona, the assistant, bookings and Instagram.

- A new area is a new file, `internal object FooRoutes : DashboardFeature(DashboardModules.FOO)`, plus
  one line in `DashboardFeatures.all`. `ArchitectureBoundariesTest` fails when a feature object is
  missing from the list.
- `DashboardDeps` carries what the features share (Mongo, tenants, users, the pipeline factory, the
  persona compiler, the AI client, runtime config, channel bindings, Instagram, the assistant
  extension, and one `InboxService`). A feature that needs something new gets it there, not as another
  parameter of `dashboardRoutes`.
- Route files with their own wiring (agents, integrations, email, timesheets, the employee portal,
  companies, account, notifications, WhatsApp and Instagram onboarding) stay `Route` extension
  functions, behind the same gate.
- `AppRoutes.kt` has `Routing.installRoutes(AppServices)`, every route the server answers.
  `Application.kt` builds the `AppServices`; HTTP tests build one with doubles.

## The module gate

`requireModules(vararg modules, denial) { … }` (`dashboard/ModuleGate.kt`) puts routes behind a
route-scoped plugin that runs after the `dashboard` sign-in: no session answers 401, a company
without every listed module answers 403, and otherwise the `DashboardContext` is stored for the
handler, which reads it with `call.moduleContext()`. `moduleContext()` throws when no gate is above
the route, so a handler can't run ungated by accident, and `DashboardRouteAuditTest` (below) fails for
a route that isn't behind one.

- **Denial bodies are what each area answered before.** `SILENT` is a bare 403 (most areas);
  `MODULE_DISABLED` is `{"error":"module_disabled"}` (agents, integrations, email).
- **Nested gates take the union.** Invoicing a quote needs quotes and invoices, so it nests
  `requireModules(INVOICES)` inside the quotes area, and turning a quote into a Serviços row nests
  `requireModules(SERVICES)` there (likewise Serviços → invoice, and agents' client
  automation inside `agents`). Ktor runs only the innermost installation of a
  route-scoped plugin, so the nested gate adds the modules of the gate around it (`enclosingGate()`);
  without that, the outer check would be skipped.
- **Open routes say why.** `openToEverySession(reason) { … }` marks what every session needs whatever
  its modules: `/me` and the company's logo (`/me/logo`), the account, notifications, switching
  company, connecting a channel. The reason is required and appears in the route tree the audit reads.
- **Checks that depend on the request stay in the handler**: a payment to an employee needs
  `employees`, linking a payment to a client needs `clients`, emailing a quote or an invoice needs
  that module, recording what an email suggestion led to needs the record's module, and the assistant
  only offers the tools of enabled modules.
- An employee's sign-in never carries modules; the validator limits it to `/app/api/me`, `/me/logo`,
  `/account*` and `/portal/*` ([employee-portal-and-time-clock.md](employee-portal-and-time-clock.md)).
- `Route.intercept` is deprecated in Ktor 3, hence a route-scoped plugin; its `on(AuthenticationChecked)`
  hook runs after the JWT validator has stored the context.

## Routes, services, repositories

Route files read the request and map results to HTTP. `crm/api/` holds the CRM's request and
response types and the helpers the dashboard and the backoffice share. `crm/` holds the services,
the drafts they validate and the repositories. `http/` is the error contract and depends on no
feature.

- **CRM writes have one implementation.** The dashboard, the backoffice (`admin/TenantAdminRoutes.kt`),
  the bot's `CrmTools` and the assistant all write through `ClientDirectory` (a client's required
  fields, details and custom fields, phone uniqueness, create-or-restore by phone), `QuoteService`
  (create, revise, status, invoice, turn into a Serviços row) and `InvoiceService` (create, paid, tax
  office code, installments, cancel, delete, invoicing Serviços). Services take and return domain
  types and outcomes (`ClientDirectory.Outcome`, `InvoiceService.ServicesInvoiced`), never HTTP, so
  each caller keeps its own answers: the routes their 4xx bodies, `CrmTools` the exception messages
  `MessagePipeline` matches.
- `ClientDirectory`, not `ClientService`: `crm.model.ClientService` is already a Serviços row.
- **Work an invoice bills outside the CRM** is an `InvoicedWork` hook given to `InvoiceService`, which
  cancel and delete call with the invoice's id. The dashboard's `TenantCrm` passes the one that frees a
  project's measurement, so `crm/` never imports `building/` (that would be one more domain cycle).
- **Building follows the same layering** (PR #88): services in `building/` (`ProjectService`,
  `MeasurementService`…) take drafts and return an `Outcome` (done, refused with a code, not found),
  `building/api/` has the requests, DTOs and views, and the routes in `dashboard/building/` map outcomes
  to 400 or 409 with the error contract's body. Its quote and invoice writes go through `QuoteService`
  and `InvoiceService`.
- **Lists don't look up per row.** Quotes, invoices, Serviços and payments load the names they show
  with one `findByIds` per kind (clients, suppliers, employees) and map through the batched `dtos(...)`
  in `crm/api/CrmDtos.kt`. The assistant's payables and conversation tools do the same, and so do the
  Building lists (quotes and invoices have `findByIds` too).
- `ArchitectureBoundariesTest` checks that nothing in `crm/` outside `crm/api/`, nor in `building/`
  outside `building/api/`, imports Ktor's server, that `http/` imports no feature, that dashboard route
  files don't call `getCollection` (a query goes in a repository, or in a service for reports like
  Home), and that every feature is registered.
- Not on the services yet: agents' CRM actions (`agents/actions/CrmActions.kt`) write through the
  repositories with their own rules, except setting a quote's status, and the backoffice's tenant
  stats count collections directly. Quote and invoice creation still writes before checking that the
  client exists (answering 400 `client not found` after the write), kept as it was.
- Gradle modules per domain were considered and not done: [`docs/plan-gradle-modules.md`](../docs/plan-gradle-modules.md).

## The assistant's tools and Home

`AssistantTools` keeps the tool list and its tables (`SPECS`, `MODULE_OF`, `READ_ONLY`,
`DEFINITIONS`) and hands each call to its group in `dashboard/assistant/`: overview, clients,
billing, payables, conversations. A new tool is a spec line plus a function in its group.
`OverviewService` builds Home from one query class per card in `dashboard/overview/` (money, sales,
directory, inbox, calendar, agents, attention, recent). Both splits were checked byte for byte: the
tool definitions sent to the model, and Home's JSON for five module mixes.

## The error contract

Every refusal is `{"error": code, "detail": field}`, `detail` only when it names a field
(`http/ApiErrors.kt`). `configureStatusPages` (`plugins/StatusPages.kt`) answers:

| Cause | Answer |
|---|---|
| `ApiException` from a handler | its status and code |
| malformed or incomplete JSON, a field of the wrong type, an empty JSON body | 400 `invalid_body` |
| a missing or unconvertible query parameter (`getOrFail`) | 400 `invalid_request` |
| a body in a format nothing reads (form, text, no content type) | 415 `unsupported_media_type` |
| a body over a limit | 413 `payload_too_large` |
| anything else | 500 `{"error":"Internal server error"}`, logged with its stack |

- Handlers read ids, dates and statuses with `call.idParam`, `idValue`, `dateValue` and `enumValue`
  (`http/RequestValues.kt`), which throw `ApiException(400, invalid_id | invalid_date | invalid_status,
  field)`. Enum names must match exactly, as `valueOf` did. Domain refusals keep their own codes
  (`phone_taken`, `already_invoiced`, `payment_paid`, `invalid status` on a quote's PATCH, …).
- Until PR #87 these answered 500: malformed JSON, bad ObjectIds in paths, queries and bodies, unknown
  statuses, unreadable dates, and a logo upload that wasn't multipart (now 415). Creating a client from
  the backoffice with a taken phone answered 500 and now answers 409 `phone_taken`, as the dashboard did.
- **Gotcha:** Ktor's engine catches `CannotTransformContentToTypeException` (an empty body, or a
  content type no converter reads) in its own interceptor at the `Plugins` phase and answers a bare
  415, so StatusPages never sees it. `configureStatusPages` intercepts the same phase, inside the
  engine's interceptor, and rethrows it as `invalid_body` (JSON content type) or
  `unsupported_media_type`.

## HTTP tests

- `testing/DashboardApi.kt`: `dashboardApiTest(mongo) { … }` mounts `installRoutes` with StatusPages,
  serialization, WebSockets and both sign-ins over the Testcontainers Mongo. Meta, Google and
  Instagram are a MockEngine answering 503, the AI is `FakeModel`, and the pipeline factory is the real
  one: a relaxed mock hands every company a WhatsApp client, so `no_channel` paths would pass as sent.
  `company(modules, channels)` gives admin, member and operator tokens; `employeeToken` an employee's.
- `DashboardRouteAuditTest` reads the mounted route tree. Every `/app/api` endpoint must be gated,
  open with a reason, or an employee page; each gate must refuse a company without each of its modules,
  with its denial body; an employee's token gets 401 on every team endpoint; a company with every
  module passes every gate. A route added without a gate fails it, listed by method and path.
- Per area, an `*ApiTest` (`dashboard/crm/`, `dashboard/inbox/`, `dashboard/settings/`,
  `dashboard/building/`, `OverviewApiTest`, `BookingApiTest`, `InstagramApiTest`) covers its gate, isolation (company B
  can't read, change or list company A's records, and A's are unchanged after B tries) and its error
  codes. `StatusPagesTest` pins the contract on its own.
- **Gotcha:** the route tests older than #87 (`EmployeeWorkRoutesTest`, `PersonaRoutesTest`,
  `AgentRoutesTest` and about a dozen more) mount their routes by hand, without StatusPages. There a
  handler's `ApiException` (the `invalid_id`, `invalid_date` and `invalid_status` of
  `http/RequestValues.kt`) isn't turned into its 400, so a test that asserts one of those codes needs
  `configureStatusPages()` in its `application { }`, as `BillingRoutesTest` has since the merge of
  `main` into #87, or `dashboardApiTest`.
