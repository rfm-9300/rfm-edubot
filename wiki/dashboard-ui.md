---
updated: 2026-10-20
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
  server-side. The classic Home code, unreachable since then, was deleted on 2026-10-17 (PR #84,
  [dashboard-frontend.md](dashboard-frontend.md)).
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

Since then `/app`'s Timesheets and Persona tables took `.tbl--stack` too, and #86 (below) moved its
breakpoint from 700px to 920px.

## Backoffice on phones, second pass (PR #86, 2026-10-18)

Rodrigo found the backoffice still not made for phones after #51: nothing scrolled sideways, but it
was the desktop layout squeezed down. Measured in headless Chrome at 320, 375 and 414px with touch
emulation, and at 768 and 1280px, in both themes:

- **One sticky top-bar row.** The bar was two rows that never left the screen (about 140px of an
  812px phone, 180px of a 640px one): menu, crumb, theme, Log out and New, then the search. The
  backoffice's `.topbar--one-row` keeps the search on the first row; under 620px the bar is menu,
  search and New, the search takes the crumb's place (the page title says the same), and the theme
  switch and Log out sit at the end of the menu (`.sidebar__session`; `theme.js` paints and wires
  any `[data-theme-toggle]` and closes the menu on `[data-nav-close]`). `/app` keeps two rows, since
  its bar also holds the bell and the account. Signed out, the crumb is hidden too: it read
  "Tenants" over the sign-in card.
- **Shorter cards.** A tenant card was about 320px tall at 375px, two per screen: status, messages
  and last activity each took half a line under a label, channel pills wrapped into three-line
  blobs in half the width, and Open dashboard took its own line. It is about 230px now: the status
  pill beside the name, the channels across the card (`.tbl__wide`), then messages, last activity
  and Open dashboard on one line. Dashboard users and agents show their status the same way.
  - The status pill is in the markup twice on purpose. CSS can't put a later cell beside the first
    line of the first cell without guessing its width (a fixed corner column, or absolute
    positioning with a padding reserve), so the name cell's `.tbl__title` repeats it as a
    `.tbl__aside`, hidden in the table, and the card hides the column's cell (`.tbl__stack-hide`,
    `display: none`, so a screen reader meets it once).
  - **The flowing line is opt-in (`.tbl--flow`).** Tried first on every `.tbl--stack`, it broke
    `/app`'s Timesheets cards: a shift with a break pushed its values onto other lines than the next
    shift's, so the cards stopped lining up. Tables compared value by value keep the grid.
- **Cards from 920px, not 700px.** At 768px (a tablet held upright) the tenant table was 848px wide in
  a 734px panel: names one word per line, slugs broken at every hyphen, Open dashboard behind a
  sideways scroll. Every `.tbl--stack` now stacks where the sidebar becomes a menu, which also turns
  `/app`'s Timesheets table into cards on tablets (its Status column had scrolled out of view).
- **Platform settings' Save follows the screen.** It sat at the top of a page about seven screens
  long on a phone. It is now a `.savebar` at the end (Save, and Discard behind a confirm), sticky
  while a field has a change, on the desktop too; the hero keeps Reload. Under 860px a key shares
  its line with its pills and a lone Reveal or Clear sits beside the field, about 120px a row
  instead of 200.
- **Shared rules that reach `/app` on phones:** on touch phones (under 560px with `pointer: coarse`)
  `.btn--sm`, `.chip` and toolbar selects are 36px, the drawer's × 40px, `.form__check` 32px; under
  560px a `.notice`'s buttons go under its text; a `.dash-facts` value wraps (a channel's name and
  id ran past a 320px screen and the panel clipped it); the top bar's search input fills its field.
  Checked on `/app`'s Clients, a client record, Agents, Timesheets and Persona: only those controls
  changed.

The desktop at 1280px stayed pixel-identical (only the sidebar clock differed), Platform settings'
Save apart. Still open: with the sidebar showing (920 to about 1200px) the tenant table, 896px
wide, scrolls inside its panel, and the dashboard-users table scrolls inside the 640px desktop
drawer when emails are long. Several pt-PT backoffice strings are English in the catalog ("Channels",
"Platform", "unchanged", "+ Add channel"); some look deliberate ("Os channels são opcionais").

## Directory column picker (shipped 2026-10-07, `b84ef38`)

Rodrigo asked to choose which columns a list shows, now that companies add their own client fields.
His choices: each person picks, kept **in the browser only** (not on the account, not company-wide),
and on all three directories (Clients, Suppliers, Employees).

- A ghost **Columns** button in the directory's `.panel__tools` opens a drawer of `.form__checks`
  (Name ticked and disabled), plus a "Your fields" group on Clients. Boxes apply at once; the foot
  restores the defaults. Front end only, no API (`app/directories.js`; each list's columns are beside
  it, as `clientColumns()` in `clients.js`).
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

## Moving columns (2026-10-20)

Rodrigo asked to drag a list's columns into another order (Clients, Services and the like). The rules
for building such a table are in [`design-system/components.md`](../design-system/components.md#table).

- **Which tables:** every `/app` list: Clients, Suppliers, Employees, Services, Quotes, Invoices,
  Financeiro, Payments, Catalog, the bookings agenda and an employee's My services. Not the week and
  month summaries (aggregates whose columns are the point), the bookings week grid and its "to confirm"
  panel, record tabs, Contacts (its `panelTable` has no head to hold the reset button), Instagram's
  feeds, the self-contained modules (Agents, Timesheets) or the backoffice.
- **Built on column definitions,** as the directories already were: each list's columns sit beside it
  (`serviceColumns()`, `quoteColumns()`…), and `app/columns.js` holds the order, the head and the drag.
  `columnCells(columns, row, ids)` now takes the row before `ids`. The conversion left the markup the
  same in the default order: all 17 list and summary views compared in headless Chrome before and after,
  whitespace between cells aside.
- **Kept in the browser,** like the column picker: `localStorage` key
  `tableColumnOrder:<tenantId>:<userId|operator>:<table>` holds the ids of the columns that were shown
  at the last move. A hidden column, or a company field added since, comes right after the column it
  follows by default. Back in the list's own order (dragged back, or the moved column hidden in the
  picker) the key goes, and with it the **Restore the column order** button, which shows only while
  the order differs. The first version saved every column, hidden ones too: moving Phone and back left
  postal code and city shifted, so the button stayed with nothing visibly changed. The directories'
  **Restore the default columns** puts the order back too.
- **Pinned:** the Services tick box (`pin: 'start'`) and each row's actions (`pin: 'end'`: PDF, mark
  paid, edit and delete, confirm a booking) don't move, and a drop beyond them lands beside them.
- **Pointer Events, not HTML5 drag and drop,** which Android Chrome doesn't fire for touch. A mouse
  drags after 4px. A finger or pen holds the heading 350ms first, so a swipe across the headings still
  scrolls the table; once it drags, a non-passive `touchmove` on the heading row stops the page
  scrolling. Gotcha: Chrome makes touch events uncancelable while a fling runs, so a hold right after a
  swipe can turn into a scroll; holding again works. ← and → move a focused heading, Escape cancels a
  drag, and a table wider than its panel scrolls while the pointer is within 32px of its edge, faster
  nearer the edge. A flat 40px zone at 12px a frame ran a phone's table away from a drop just inside it.
- **Look:** the dragged column takes the `--accent-soft` selection tint and a 2px `--ink` line down
  the table marks where it lands. A first version also floated the heading under the pointer; it hid
  the heading it passed over and left a gap where it had been, so the line alone shows the drop.

Verified locally in headless Chrome: mouse drags on every list, Escape, a plain click, the arrow keys
with the focus kept, reload, both reset buttons, the picker interplay, a saved order with unknown and
missing ids, pinned columns, row actions and record links after a move, auto-scroll on a wide table,
a hold-and-drag and a swipe at 375px with touch emulation, light and dark, pt-PT, English and
Spanish; `DashboardScriptsTest` passes.
