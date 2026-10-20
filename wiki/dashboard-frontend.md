---
updated: 2026-10-20
---

# Dashboard frontend: how the dashboards' scripts are split

How the JavaScript of `/app` and `/backoffice` is organized, what the two share, the rules that keep
them loading, and where new code goes. The UI rules (classes, tokens, strings) are in
[`design-system/`](../design-system/AGENTS.md); the decisions behind the look are in
[dashboard-ui.md](dashboard-ui.md).

## One script per page, one global scope (PR #84, 2026-10-17)

Neither page has a build step: each `index.html` loads plain scripts with `defer`. Until PR #84 nearly
all of `/app` was `app/app.js`, 9,601 lines (574 KB) that every dashboard change touched, beside the
self-contained modules that had already left it (`agents.js`, `assistant.js`, `email.js`, `menus.js`,
`timesheets.js`, `doc-template.js`), and the backoffice was `backoffice/app.js`, 1,381 lines that copied
about a dozen of `/app`'s helpers. At Rodrigo's request, #84 split both into one script per page or
shared concern, without changing any code, then moved the helpers they shared into one file,
`admin/common.js`.

- **A page's scripts share one global scope.** They are classic scripts, so a `function` or top-level
  `const` declared in one is visible in all the others, exactly as inside the one file. Nothing is
  imported or exported, and a function can call any other. The persona walkthrough reading `state`,
  and CDP calls such as `openInvoiceDetail()` ([gotchas.md](gotchas.md)), work as before.
- **They only declare at their top level.** No script runs code at load time that needs another one:
  `app.js` loads last and its `init()` starts the page. So the order in `index.html` is free, apart
  from `app.js` last. A top-level `const` whose initializer calls into another script would break this
  without any check noticing; keep initializers to literals, `I18N.section()` and arrow functions.
- **`admin/common.js` is part of both scopes.** Each page loads it (`defer`) before its own scripts,
  from `/admin/{asset}` like the stylesheet and catalogs, and it follows the same rules.
- **Self-contained modules work differently.** Each is an IIFE that exposes one `window.*UI` object
  and never reads the global scope; `/app`'s `app.js` hands it helpers through `init(deps)`
  (`agentsDeps()`, `menusDeps()`…). That fits a feature that keeps to itself. Code that opens the rest
  of the dashboard (a client record, an invoice, a drawer trail) belongs in the shared scripts, where it
  can call `openClientDrawer()` directly.
- **`DashboardScriptsTest`** (`./gradlew test`) checks both pages, `admin/common.js` included: it fails
  when an `index.html` misses one of its folder's scripts or `/admin/common.js`, when two scripts in a
  page's scope declare the same top-level name, or when one of them has top-level code other than
  declarations (each `app.js`'s closing `init();` aside). A duplicate name is worse than in one file: a
  second top-level `const` throws when its script loads, so that whole script is missing, and a second
  `function` silently replaces the first.
- **Flat folders:** `/app/{asset}`, `/backoffice/{asset}` and `/admin/{asset}` serve one path segment
  and only map `.js` and `.css` ([architecture-review.md](architecture-review.md)), so the scripts sit
  directly in their folders.
- **The classic Home went with it.** `/app` always runs the minimal skin, so `renderOverview()` only
  ever handed over to the cockpit; the classic Home and nine helpers only it used were deleted, and the
  cockpit is now `renderOverview()`.

## What the pages share: admin/common.js

The DOM shortcuts (`$`, `$$`), `escapeHTML()`, `toast()`, `confirmDialog()`, `apiError()` and
`apiFetch()`, Google sign-in (`prepareGoogleSignIn()`, `googleIdToken()`), Meta's SDK and Embedded
Signup (`loadFacebookSdk()`, `waitForWhatsAppSignupMessage()`, `facebookLoginForBusiness()`), and
`instagramConsent()`. Decisions that matter later:

- **Each page keeps its own `api()`**, one line over `apiFetch()` with its token (`dashboardToken` or
  `adminToken`) and its `signOut()`, which also serves Log out and, in `/app`, the PDF and media
  downloads. The backoffice's errors gained `field`, `limit` and `body` that way.
- **`confirmDialog()` without an `okLabel`** shows the label the page's markup names (the OK button's
  `data-i18n`: `app.confirm` or `backoffice.confirm`). Every call passes its own label anyway.
- **`instagramConsent(authorizeUrl)`** opens Instagram's consent popup and resolves with what the page
  it comes back to posts (`{ status, reason }`), or with `null` when the browser blocked it. Each page's
  `connectInstagram()` keeps its endpoint and what it reloads, and each `handleOAuthPopup()` stays on its
  page, since `/app`'s also relays Google's consent over a `BroadcastChannel`.
- **Not shared on purpose:** the drawers, because `/app`'s keeps a trail of the drawers opened from one
  another and holds focus inside, while the backoffice's has a save footer and focuses × on touch
  screens ([dashboard-ui.md](dashboard-ui.md)); and the date formatters, because `/app` shows dates in
  the company's timezone and the backoffice in the browser's.

## Where things are

`/app`:

| Script | Holds |
|---|---|
| `core.js` | `state`, the session token, `api()` and `signOut()`, catalog sections (`STR`, `CRM`…), formatting (money, dates, periods, `relTime`), what invoices received and owe (`invoicePaidEur`…), status labels and pills |
| `drawer.js` | `openDrawer()`, `closeDrawer()`, the drawer trail (`openFrom()`, `linksBackTo()`) |
| `components.js` | Shared markup: `hero()`, `statCards()`, `crmPanel()`, `panelTable()`, `periodNav()`, `dashFacts()`, `detailHead()` / `detailMeta()` / `itemsTable()` |
| `records.js` | The client, supplier and employee records' blocks: contact links, record card, KPIs, worklist rows, delete / archive / restore |
| `columns.js` | The list tables' columns: head and cells from each list's column definitions, the order each person drags them into, the drag ([dashboard-ui.md](dashboard-ui.md)) |
| `directories.js` | The three directories' Active / Archived switch and column picker (which columns show) |
| `account.js`, `notifications.js`, `companies.js` | Sign-in screen and account drawer; the bell; the company switcher and Settings → Companies |
| `home.js` | Home |
| `inbox.js`, `whatsapp-templates.js`, `instagram.js` | Contacts, Conversations and the template drawer; Settings → WhatsApp templates; Instagram |
| `clients.js`, `payees.js`, `submissions.js` | Clients (list, record, form, own fields); Suppliers and Employees; registered services and an employee's sign-in |
| `services.js`, `billing.js`, `quotes.js`, `invoices.js`, `financeiro.js`, `payments.js`, `catalog.js` | Serviços; what the document forms share (pickers, line items, PDF, sending by email); the billing pages |
| `bookings.js`, `persona.js`, `settings.js`, `integrations.js` | Bookings; Persona; Settings; connecting Instagram, Gmail and WhatsApp, and the OAuth popup |
| `app.js` | The shell: `bootAuthed()`, the nav, `setActive()` / `loadModule()` / `render()`, the modules' deps, `init()` |

`/backoffice`:

| Script | Holds |
|---|---|
| `core.js` | The operator's token, `state`, `api()` and `signOut()`, formatting and catalog labels, the drawer |
| `login.js` | The sign-in screen (Google or the platform password) |
| `tenants.js` | The tenants list and filters, a tenant's record with its users and agents, suspend / activate / delete / restore |
| `tenant-form.js` | The new and edit form: modules, channels, connecting Instagram and WhatsApp, the OAuth popup |
| `admins.js`, `backups.js`, `platform-settings.js` | The other three views |
| `app.js` | The shell: `loadAll()`, the sidebar's counts, `setView()`, `init()` |

New code goes in the script of the page it belongs to. A helper that several pages of `/app` use goes in
`core.js` (logic and formatting) or `components.js` / `records.js` (markup); one both dashboards need goes
in `admin/common.js`. A new `/app` page gets its own script, a `<script defer>` before `app.js`, and its
branches in `loadModule()` and `render()`.

## How the split was checked

Every top-level statement moved byte for byte, with only comments added (a header per file). A scope
analysis (acorn and eslint-scope) confirmed that each original statement appears exactly once (628 in
`/app`, 107 in the backoffice), that no name is declared twice, and that the only code run at load time
is `init()`. Headless Chrome then rendered every `/app` page, Settings section, Persona tab and the main
drawers (108 snapshots), and every backoffice view, tenant filter and drawer (36), on mock data with a
frozen clock, before and after each step: identical. Two runs of the same build differed only in the
Email list's order, which comes from the server.

Moving the shared helpers did change code, so it got its own checks besides the snapshots: in both
pages a 401 and Log out sign out, `confirmDialog()` shows its labels and answers, failed calls keep the
server's code and body, Google sign-in goes through `googleIdToken()` (with a stub SDK and intercepted
server calls), and Instagram's consent popup reports connected, refused and blocked, with the real page
as the popup. The three walkthroughs pass (Persona 74, Email 15, AI Assistant 16), and so does
`./gradlew test` (830).

## Not done yet

- The stylesheet still has rules only the classic Home used: `.snapshot*`, `.home-grid`,
  `.home-title-row` and `.home-customize` match nothing in the pages now.
- Four helpers read "the catalog's text, or this when the key is missing", each its own way:
  `catalogTextOr()` and `googleTextOr()` in `/app`'s `core.js`, `labelOr()` in the backoffice's and
  `known()` in `timesheets.js`. `i18n.js` could offer one.
