---
updated: 2026-10-09
---

# Dashboard UI decisions

Why `/app` and the backoffice look and behave the way they do. The binding rules (tokens, classes, i18n, checklists) are in [`design-system/`](../design-system/AGENTS.md); this page holds the decisions and history behind them.

## Dashboard look: one skin, "Clean Ops" (switch removed 2026-09-29, shipped the same day, `166ad0f`; backoffice joined, shipped 2026-10-05, `c0738d0`)

On 2026-09-28 `/app` gained an optional **minimal** CRM skin next to **classic** (pastel + emoji),
chosen per browser from a topbar toggle. On 2026-09-29 Rodrigo asked to keep only the minimal one,
and a customer asked for a stronger identity, so the switch is gone and minimal was
restyled as "Clean Ops": `#F7F8FA` canvas, white cards, `#111318` text, yellow `#F5D90A` only for
actions, selections and small highlights, yellow-soft `#FFF9D6` for the active nav item, icon wells
and selected chips, and a discreet "Powered by The Bots Lab" credit under the tenant's own brand.
Decisions that matter for future work:

- The skin is **fixed in markup**: `/app/index.html` declares `<html data-layout="minimal">`, and
  since 2026-10-04 so does `/backoffice/index.html`, because Rodrigo said the backoffice didn't
  match the rest (shipped 2026-10-05, `c0738d0`, with everything below). `/admin` only redirects,
  so no page shows the classic (violet) layer on its own; it is just the base the skin overrides.
  `admin/theme.js` only handles light/dark (`ui:theme`); an old `localStorage.uiLayout` is ignored.
- Backoffice on the skin: its nav uses `data-view`/`href`, not `data-tab`, so its outline icons are
  keyed on `href`; the tenant drawer's modules are a `.form__checks` grid of `.form__check` (not
  pills) and its channels a `.lines--channels`; setup warnings are `.notice--warn` banners; platform,
  role and user status show catalog labels (`app.channel_*`, `app.accountRole*`,
  `backoffice.userStatus`), never the raw enum. Two shared-CSS gotchas surfaced: `.topbar__search`'s
  `display: flex` beat the `hidden` attribute, so the backoffice search had always shown on Admins,
  Backups and Settings (now `.topbar__search[hidden]`, with `.topbar__actions` pinned to the
  topbar grid's last column); and the sticky `.drawer__foot` covered the last 28px of every drawer
  scrolled to the bottom, `/app`'s too (sticky offsets are measured inside `.drawer__body`'s 28px
  padding, and the foot's -28px margin only helped in flow). Fixed the same day without relying on
  how browsers inset sticky: `.drawer__body:has(.drawer__foot)` drops its bottom padding and the
  foot its negative margin, so the resting layout is unchanged.
- Tenant rows were seven wrapping buttons each, so at Rodrigo's choice a tenant now opens as a
  **record** like an `/app` client: the row keeps only Open dashboard (Restore under Deleted), the
  name is a `.tbl__open` button so keyboards reach it, and the drawer holds the profile card (Edit,
  Suspend/Activate, Delete; Open dashboard, Users, Agents, Reload pipeline), four KPIs and the
  channels. Edit, Users and Agents get `openDrawer({ back })`, a one-level version of `/app`'s
  drawer trail; a new tenant opens its record; Escape closes a confirm without the drawer behind it.
- **Yellow is a fill, never text**: yellow on white is about 1.4:1. Text on tints uses
  `--accent-deep` (dark gold `#735f00` in the light skin) or `--ink`; trend lines use `--accent-deep`.
- **Focus is tokenized** (`--focus-border`, `--focus-ring`): classic resolves to the old
  accent ring; the light skin uses an ink edge plus a yellow halo, because a yellow ring alone is
  invisible on white.
- Minimal is **token overrides + scoped rules** in the one shared stylesheet. The
  `html[data-layout="minimal"][data-theme="dark"]` block must redeclare every color the light block
  sets, or light values leak into dark (equal-specificity tie).
- Gotcha: the skin's `.tbl` / `.panel__head` rules out-rank the compact `.record .tbl` and
  `.panel--card` rules, so the wider list-page spacing is scoped to `.view > .panel` (the directory
  card); drawers and Home cards keep 16px.
- Home is always the dense cockpit (KPI strip with sparklines, needs-you queue, agenda, cash flow,
  aging, pipeline, inbox activity, top clients, feed, tiles) from `GET /app/api/overview?extended=1`
  (`cashFlow`, `activity`, `agenda`, `recent`, `topClients`); blocks for hidden cards are skipped
  server-side. The classic Home code in `app.js` is unreachable and can be deleted.
- Reusable pieces added for the other list pages: `statCards` items take an `icon` (yellow-soft
  well), and `crmPanel({ emptyArt })` takes a small inline line illustration (Clientes: a client, a
  spray bottle, sparkles).

## Backoffice on phones (PR #51, shipped 2026-10-06, `5b82fe9`)

A Cursor cloud agent's PR (branch `cursor/backoffice-mobile-layout-14fe`, opened 2026-10-05). Every
backoffice table scrolled sideways on a phone: at 375px the tenant list was twice as wide as its box,
with status and Open dashboard off-screen. Since #51 nothing in the backoffice scrolls sideways from
320px up, and the desktop is unchanged (drawers pixel-identical at 1280px, per the agent).

- **`.tbl--stack` is opt-in:** under 700px each row becomes a card. The row's name heads it, the
  other values sit under their column's label in four columns (two under 560px), and the actions
  close it. Labels come from a `data-label` on each cell that reuses the heading's catalog key, so it
  adds no strings. The backoffice's tenants, admins, backups, dashboard users and agents tables use
  it; `/app` tables don't yet.
- **Shared-stylesheet rules that reach `/app` too:** under 560px `.form__grid` is one column and the
  drawer pads 16px. The full-width drawer now has square corners in the minimal skin, whose radius
  rule had outranked the documented square corners. Toasts wrap at the screen width. On touch screens
  (`@media (pointer: coarse)`) fields are 16px, because iOS Safari zooms into any smaller field.
- **Touch drawers** open with focus on × instead of the first field, so Android doesn't pop the
  keyboard over the drawer; the desktop still focuses the first field.
- **Signed out,** the backoffice shows only the theme switch (no menu, search, Log out or New),
  closes any open drawer, and returns to the view in the address bar after sign-in. Before, the
  menu's Tenants link rendered an empty list over the sign-in card.
- Channel rows take two lines under 560px, so a 15-digit WhatsApp id shows in full. The design-system
  docs gained the breakpoints (560px phone, 700px stacked tables) and a 375px check in the agent
  checklist.

Merging it: it conflicted with `17425b5` in `admin/style.css` and `design-system/components.md`. In
the 560px line-items block, #51's two-line channel rows now sit with that commit's services and
installment rows, and `.form__checks--wide` (the payment form's service ticks) drops to one column on
a phone. `main` was merged into the branch (`9218779`) and checked at 375px with no sideways
scrolling. CI deployed `5b82fe9`, health ok, 0 `ERROR` lines. The agent ran no Kotlin tests because
its VM had no Docker; CI ran them.

## Directory column picker (shipped 2026-10-07, `b84ef38`)

Rodrigo asked to choose which columns a list shows, now that companies add their own client fields.
His choices: each person picks, kept **in the browser only** (not on the account, not company-wide),
and on all three directories (Clients, Suppliers, Employees).

- A ghost **Columns** button in the directory's `.panel__tools` opens a drawer of `.form__checks`
  (Name ticked and disabled), plus a "Your fields" group on Clients. Boxes apply at once; the foot
  restores the defaults. Front end only (`app.js`, no API).
- **Stored as overrides, not lists:** `localStorage` key `tableColumns:<tenantId>:<userId|operator>:<table>`
  holds only the columns someone turned away from their default. The defaults are the columns the lists
  always had, and an own field's default is its company-level "show as a column" (`showInList`), so a
  field added later appears for everyone who never touched it. Removed fields' keys are pruned on the
  next change.
- New optional columns: a client's contact person, postal code, city, email and NIF; an employee's NIF,
  date of birth and address. Suppliers only gained hiding (their DTO has no NIF or email). With City
  shown, the client Address column drops the city.
- **Wide-table gotcha:** a `.tbl` wider than its panel scrolls in `.tbl-wrap`, and auto table layout
  then squeezes every wrappable cell to its longest word (addresses one word per line, phones split at
  their spaces). Fixed with two documented cell helpers in the shared stylesheet: `.nowrap` (phone, NIF,
  postal code, dates, numbers) and `.long` (11em minimum for names, addresses, free-text fields). `.id`
  codes take neither. Even before this, the `/app` directories already scrolled sideways inside their
  panel at 375px; they don't use `.tbl--stack`.

Verified locally with headless Chrome: unchanged default columns, a 9-column choice, reload, reset,
suppliers in dark pt-PT, employees at 375px in Spanish, no page errors; catalogs in parity. CI deployed
`b84ef38` (Mongo not bounced, 0 `ERROR` lines); the app sends no cache headers, so a reload shows it.
