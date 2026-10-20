---
updated: 2026-10-20
---

# Cross-cutting gotchas

Traps that bite anywhere in this codebase or its tooling. Feature-specific gotchas stay on their feature's page.

## kotlinx `encodeDefaults` is off

- kotlinx `encodeDefaults` is off in the Ktor JSON config and in any `Json { }` that doesn't set it:
  every property left at its default is **omitted**, not only in all-default DTOs. Empty lists
  vanish, so the frontend must treat a missing array as empty. Outbound API clients build their own
  `Json` and must set `encodeDefaults = true`, or constant fields are never sent: `WhatsAppSignupClient`
  lacked it, so Embedded Signup's `/register` went out as `{"pin":…}` without
  `messaging_product = "whatsapp"`, Meta answered `#100`, and `/app/api/whatsapp/connect` returned 502
  in prod (fixed with a request-body test and shipped 2026-09-29, `b48bf16`).

## A `private companion object` breaks Ktor `receive`

- **Gotcha: a `private companion object` on a `@Serializable` request class** makes Ktor's `receive`
  fail with `IllegalAccessException` (a 500): kotlinx's reflective serializer lookup can't reach the
  plugin-generated `serializer()` on a private companion. Tests that construct the class directly miss
  it; `Json.decodeFromString(serializer(typeOf<T>()), …)` catches it.

## Ktor 3: the engine's 415, route-scoped plugins, relaxed mocks

- **The engine answers an unreadable body itself.** `CannotTransformContentToTypeException` (an empty
  body, or a content type no converter reads) is caught by an interceptor Ktor's engine installs at
  the `Plugins` phase, which answers a bare 415 before StatusPages runs, so an `exception<…>` handler
  for it never fires. `configureStatusPages` intercepts the same phase to turn it into the error
  contract ([dashboard-api.md](dashboard-api.md#the-error-contract)).
- **A route-scoped plugin runs once, at its innermost installation.** Installing it again inside a
  route that has it replaces the outer check for those routes instead of adding a second one, which is
  why a nested module gate carries the modules of the gate around it
  ([dashboard-api.md](dashboard-api.md#the-module-gate)). `Route.intercept`, the old way, is deprecated.
- **A relaxed mockk `TenantPipelineFactory` hands every company a WhatsApp client**, so an HTTP test
  of a path that should answer `no_channel` sees it "sent". The `DashboardApi` harness uses the real
  factory over a MockEngine.

## Test gotcha: JUnit 5 silently skips Kotlin tests that return a value

A test written as ``fun `x`() = runBlocking { ... }`` returns whatever its last expression returns.
If that is not `Unit` (for example `assertFailsWith<E> { ... }`, which returns the exception, or
`deferred.complete(Unit)`, which returns a `Boolean`), JUnit Jupiter never discovers the method: no
failure, no skip, it just does not run. Two `InstagramClientTest` cases had never run for this reason
(found 2026-09-28; they pass once fixed). Use `runBlocking<Unit> { ... }` or end on an assertion, and
check by comparing `@Test` counts with the `tests=` counts in `build/test-results/test/*.xml`.

## Test gotcha: a backoffice token's email must be on the allowlist

The `admin-jwt` validator re-checks a Google session's `email` claim against the live admin list on
every request ([auth-and-access.md](auth-and-access.md)). A test that signs its own admin JWT with an
`email` claim, as the board and vault tests do to record who did what, gets a 401 with an empty body
unless that email is in the test config's `googleSignIn.allowedEmails`; parsing that body then fails
with "unexpected end of the input". A token without `email` (a password sign-in) passes as before.

## Testcontainers and Docker Engine 29

The tests use Testcontainers 1.21.4 since 2026-10-12. Docker Engine 29 refuses API versions below
1.40 and 1.20.3 asked for 1.32, so every test that needs the shared Mongo container (`TestMongo`)
failed with "Could not find a valid Docker environment" (underneath, an empty 400 from `/info` in
`DockerClientProviderStrategy`). 1.21.4, the last 1.x release, asks for 1.44 and falls back to 1.32
only for older engines.

- **CI**: GitHub moves `ubuntu-latest` from Ubuntu 24.04 (Docker 28.0.4) to 26.04 (Docker 29.4.2)
  from 2026-10-19, and the Test job gates the image build and the deploy. On an `ubuntu-26.04` runner
  (2026-10-11) the suite failed with 1.20.3 (59 failures, 48 of them whole test classes that couldn't
  start) and passed all 796 tests with 1.21.4.
- **Before the bump**, Rodrigo's Mac hit it first with Docker Desktop 29.x and got by with
  `JAVA_TOOL_OPTIONS="-Dapi.version=1.44" ./gradlew test --no-daemon` (`DOCKER_API_VERSION` did not
  help), and the Cursor cloud environment pins the same through `~/.docker-java.properties`. 1.21.4
  already asks for 1.44, so neither should be needed (the CI check ran without one); both are harmless.
- Testcontainers 2.x prefixes every module (`org.testcontainers:testcontainers-mongodb`), moves the
  container classes to new packages and drops JUnit 4, so moving to it touches every Mongo-backed
  test, not only `build.gradle.kts`.

## The git-ignored PDF logo breaks `processResources`

Seen again 2026-09-29: the git-ignored `src/main/resources/pdf/ropaint-logo.jpeg`
can't be read on Rodrigo's Mac ("Operation not permitted"), so `processResources` fails whenever resources
change and leaves a 0-byte copy in `build/resources/main/pdf/` that makes every `PdfGeneratorTest` fail
with "Image type UNKNOWN". Workaround: `rsync -a --exclude 'pdf/ropaint-logo.jpeg' src/main/resources/
build/resources/main/`, delete the build copy, then run Gradle with `-x processResources`.

Local agent gotcha found the same day: the agent's shell sandbox could not read
`src/main/resources/pdf/ropaint-logo.jpeg`, so `processResources` (which recopies every resource
when any changes) failed and emptied part of `build/resources`. Syncing resources with `rsync`
(excluding that file) and running Gradle with `-x processResources` worked; CI is unaffected.

## Frontend: i18n section proxies and served assets

- The `I18N.section()` proxy returns the key path (`'app.foo'`) for a missing key, which is truthy.
  `STR.foo || fallback` never falls back; compare against the path instead.
- `./gradlew run` serves `/app` and `/admin` assets from `build/resources/main`, copied at start.
  Frontend edits don't show until `./gradlew processResources` runs; no restart is needed, since
  assets are re-read per request.

- **Gotcha:** `I18N.section('admin').suppliers`, a nested catalog object, returns the raw object, so a
  missing key reads as `undefined`. The `STR` / `SUB` section proxies return the key path instead. Use
  `obj[key] || fallback` on nested objects and `catalogTextOr` only on proxies.

## The `hidden` attribute loses to `display` rules

`.form__row { display: flex }` beat the `hidden` attribute, so forms showed fields meant to be hidden (the payment form's supplier and employee fields, the catalog form's booking-only fields; see [crm-directories.md](crm-directories.md)), and `.topbar__search`'s `display: flex` kept the backoffice search visible on pages that hide it ([dashboard-ui.md](dashboard-ui.md)). Any class that sets `display` outranks the user-agent `[hidden]` rule, so the stylesheet adds overrides (`.form__row[hidden], .form__grid[hidden]`, `.topbar__search[hidden]`, `.flow-menu__body[hidden]` for a folded Persona menu, whose `.panel__body` is also a `.form` grid), and a new `display` rule on an element that gets hidden needs one too.

## Checking the UI locally

- **Local UI check without a login:** sign an `operator-imp` JWT (`sub: operator`, `tenantId`,
  `typ: operator-imp`, issuer `wabot-platform`) with the local `ADMIN_JWT_SECRET`, then drive headless
  Chrome over CDP (Node 22+ has `WebSocket`) for screenshots. Seed docs with `costUsd: null`: mongosh
  stores whole numbers as Int32, and `MessageRepository`'s `getDouble` throws on them. Answer Meta-backed
  endpoints (templates, media) with CDP `Fetch.fulfillRequest` to see those screens without Meta, and
  give Chrome a fresh `--user-data-dir` each run: a reused profile restores the last tab and the script
  can attach to the wrong page. With a backoffice password set, the backoffice hands out the same
  token: `POST /admin/api/tenants/{slug}/impersonate` with its own token answers `{ token }`.

- **Mock data needs a restart to show (found 2026-10-17):** `create-mocks.js` writes the clients,
  quotes, invoices and chats without a `tenantId` (only its newer collections go to the first tenant),
  so the dashboard lists none of them until something gives them one. `TenantSeeder` does at every
  startup (on a fresh database it also creates the "Default Tenant" those records join); otherwise run
  `mocks/mongo/migrate-add-tenant-id.js`. Turn on the opt-in modules (Agents, Email, the time clock)
  for that tenant in the backoffice to see their pages.

- **Phone checks need touch emulation:** the touch rules (`pointer: coarse`: 16px fields, 36px small
  buttons and chips) only match with puppeteer's `setViewport({ hasTouch: true, isMobile: true })`; a
  plain 375px window shows the desktop sizes. To sign in by script, set `adminToken` or
  `dashboardToken` in `localStorage` on another page of the origin (`/health`), then open the
  dashboard: a `goto` from `/backoffice/` to `/backoffice/#tenants` only changes the hash, so the
  page never reloads and stays on the sign-in card. For dark mode set `localStorage.uiTheme` to
  `dark` before the page loads: `ui:theme` is the event `admin/theme.js` fires, not its key, and
  setting it leaves the page light.

- **Local UI-check gotchas:** a process started with `(cmd &)` inside an agent's tool shell dies when
  that shell exits, so run `./gradlew run` as a background job of the tool. `/app`'s own scripts are
  classic scripts sharing one global scope ([dashboard-frontend.md](dashboard-frontend.md)), so their
  top-level functions (`openInvoiceDetail`, `openPaymentForm`…) can be called over CDP to open drawers.

- **Walkthrough gotchas (2026-10-09, PR #72):** a new company's clients need a NIF and an address
  (`ClientFields.DEFAULT` requires both), so seeding through `POST /app/api/crm/clients` without them
  answers `400 tax_id_required`, and a client form opened in a walkthrough needs them typed in. The
  drawer slides in, so a puppeteer click right after it opens can land beside its fields and the
  typing goes nowhere: wait for it to settle, or focus fields through the DOM. List chips and rows
  are redrawn whenever their data changes, so click them through the DOM and wait for the new state
  (the chip's `is-on`), not just a row count the previous list may already have.

## GitGuardian flags an autocomplete value as a password

GitGuardian's "Generic Password" detector reads `accountPasswordField('acc-new', …, 'new-password', …)`
in `app/account.js` (the account drawer's new-password field, whose `autocomplete` is `new-password`)
as a hardcoded secret. Any PR whose diff carries that line fails the GitGuardian check: #84, which
moved it out of `app.js`, and #86, which merged `main` in after it. Nothing is leaked and the check
isn't required; the incident has to be marked a false positive in the GitGuardian dashboard, which
agents can't reach.

## Agent-rules gotcha: the GitNexus block is pinned (since 2026-09-29)

`gitnexus analyze` rewrites the `<!-- gitnexus:start -->` … `<!-- gitnexus:end -->` block in
`AGENTS.md` and `CLAUDE.md` on every run (no hook runs it; agents do when the index is stale).
Rodrigo dropped the block's mandatory rules, impact analysis before every edit and `detect_changes`
before every commit (stated under both Always Do and Never Do), in the root and `mobile/` files
(`8d0ed96`, `db9f1d3`). All four blocks carry `<!-- gitnexus:keep -->`, which makes analyze leave a
hand-edited block as written; without it the next run restores the rules. Side effect: the block's
symbol counts stop refreshing, because the keep path only rewrites a line that starts with
`Indexed as **name**` and the default line starts "This project is indexed…".
