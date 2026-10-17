---
updated: 2026-10-17
---

# Dashboard frontend: how /app's scripts are split

How the `/app` dashboard's JavaScript is organized, the rules that keep it loading, and where new code
goes. The UI rules (classes, tokens, strings) are in [`design-system/`](../design-system/AGENTS.md); the
decisions behind the look are in [dashboard-ui.md](dashboard-ui.md).

## One script per page, one global scope (PR #84, 2026-10-17)

`/app` has no build step: `app/index.html` loads plain scripts with `defer`. Until PR #84 nearly all of
it was `app/app.js`, 9,601 lines (574 KB) that every dashboard change touched, beside the
self-contained modules that had already left it (`agents.js`, `assistant.js`, `email.js`, `menus.js`,
`timesheets.js`, `doc-template.js`). At Rodrigo's request, #84 split it into one script per page or
shared concern, without changing any code.

- **The scripts share one global scope.** They are classic scripts, so a `function` or top-level
  `const` declared in one is visible in all the others, exactly as inside the one file. Nothing is
  imported or exported, and a function can call any other. The persona walkthrough reading `state`,
  and CDP calls such as `openInvoiceDetail()` ([gotchas.md](gotchas.md)), work as before.
- **They only declare at their top level.** No script runs code at load time that needs another one:
  `app.js` loads last and its `init()` starts the page. So the order in `index.html` is free, apart
  from `app.js` last. A top-level `const` whose initializer calls into another script would break this
  without any check noticing; keep initializers to literals, `I18N.section()` and arrow functions.
- **Self-contained modules work differently.** Each is an IIFE that exposes one `window.*UI` object
  and never reads the global scope; `app.js` hands it helpers through `init(deps)` (`agentsDeps()`,
  `menusDeps()`…). That fits a feature that keeps to itself. Code that opens the rest of the dashboard
  (a client record, an invoice, a drawer trail) belongs in the shared scripts, where it can call
  `openClientDrawer()` directly.
- **`DashboardScriptsTest`** (`./gradlew test`) fails when `index.html` misses an `app/*.js`, when two
  scripts declare the same top-level name, or when a shared script has top-level code other than
  declarations (`app.js`'s closing `init();` aside). A duplicate name is worse than in one file: a
  second top-level `const` throws when its script loads, so that whole script is missing, and a second
  `function` silently replaces the first.
- **Flat folder:** `/app/{asset}` serves one path segment and only maps `.js` and `.css`
  ([architecture-review.md](architecture-review.md)), so the scripts sit directly in `app/`.
- **The classic Home went with it.** `/app` always runs the minimal skin, so `renderOverview()` only
  ever handed over to the cockpit; the classic Home and nine helpers only it used were deleted, and the
  cockpit is now `renderOverview()`.

## Where things are

| Script | Holds |
|---|---|
| `core.js` | `state`, the session token, `api()`, catalog sections (`STR`, `CRM`…), escaping, formatting (money, dates, periods, `relTime`), what invoices received and owe (`invoicePaidEur`…), status labels and pills, `toast()`, `confirmDialog()` |
| `drawer.js` | `openDrawer()`, `closeDrawer()`, the drawer trail (`openFrom()`, `linksBackTo()`) |
| `components.js` | Shared markup: `hero()`, `statCards()`, `crmPanel()`, `panelTable()`, `periodNav()`, `dashFacts()`, `detailHead()` / `detailMeta()` / `itemsTable()` |
| `records.js` | The client, supplier and employee records' blocks: contact links, record card, KPIs, worklist rows, delete / archive / restore |
| `directories.js` | The three directories' Active / Archived switch and column picker |
| `account.js`, `notifications.js`, `companies.js` | Sign-in screen and account drawer; the bell; the company switcher and Settings → Companies |
| `home.js` | Home |
| `inbox.js`, `whatsapp-templates.js`, `instagram.js` | Contacts, Conversations and the template drawer; Settings → WhatsApp templates; Instagram |
| `clients.js`, `payees.js`, `submissions.js` | Clients (list, record, form, own fields); Suppliers and Employees; registered services and an employee's sign-in |
| `services.js`, `billing.js`, `quotes.js`, `invoices.js`, `financeiro.js`, `payments.js`, `catalog.js` | Serviços; what the document forms share (pickers, line items, PDF, sending by email); the billing pages |
| `bookings.js`, `persona.js`, `settings.js`, `integrations.js` | Bookings; Persona; Settings; connecting Instagram, Gmail and WhatsApp, and the OAuth popup |
| `app.js` | The shell: `bootAuthed()`, the nav, `setActive()` / `loadModule()` / `render()`, the modules' deps, `init()` |

New code goes in the script of the page it belongs to. A helper that several pages use goes in
`core.js` (logic and formatting) or `components.js` / `records.js` (markup). A new page gets its own
script, a `<script defer>` before `app.js`, and its branches in `loadModule()` and `render()`.

## How the split was checked

Every top-level statement moved byte for byte, with only comments added (a header per file). A scope
analysis (acorn and eslint-scope) confirmed that each of the original's 628 statements appears exactly
once, that no name is declared twice, and that the only code run at load time is `init()`. Headless
Chrome then rendered every page, Settings section, Persona tab and the main drawers on mock data with
a frozen clock, before and after: 108 snapshots, identical. Two runs of the same build differed only in
the Email list's order, which comes from the server.

## Not done yet

- `backoffice/app.js` (1,381 lines) is still one file, and still copies about a dozen helpers from
  `/app` ([architecture-review.md](architecture-review.md)).
- The stylesheet still has rules only the classic Home used: `.snapshot*`, `.home-grid`,
  `.home-title-row` and `.home-customize` match nothing in the pages now.
