# Components

Class names and markup as implemented in `src/main/resources/admin/style.css`. Copy these. Do not restyle with extra wrappers unless a pattern in [patterns.md](patterns.md) already does.

Strings in examples are placeholders — real copy goes through i18n.

## Buttons

```html
<button class="btn btn--primary" type="button">Primary</button>
<button class="btn btn--accent" type="button">Accent</button>
<button class="btn btn--ghost" type="button">Ghost</button>
<button class="btn btn--danger" type="button">Danger</button>
<button class="btn btn--sm" type="button">Small</button>
<button class="btn btn--primary" type="button"><span class="btn__plus">+</span> New</button>
<button class="iconbtn" type="button" aria-label="Close">×</button>
<button class="iconbtn iconbtn--theme" id="btn-theme" type="button">🌙</button>
<button class="iconbtn iconbtn--theme iconbtn--bell" id="btn-notifications" type="button" aria-label="Notifications, 3 unread"><svg>…</svg><span class="iconbtn__count" aria-hidden="true">3</span></button>
<button class="iconbtn iconbtn--theme iconbtn--avatar" id="btn-account" type="button" aria-label="Your account">AR</button>
```

- `.btn` and `.btn--primary` / `.btn--accent` are the same CTA (gradient + `--accent-ink` + `--glow-accent`). Prefer `--primary` for page CTAs, `--accent` for in-row / drawer save.
- `.btn--ghost` for cancel, logout, secondary.
- `.btn--danger` only for irreversible confirm. Pair with `.confirm`.
- `.btn--sm` in table action cells and compact toolbars.
- `.iconbtn` is 30×30, radius 10px. Theme toggle uses `.iconbtn--theme` (36×36 pill).
- `.iconbtn--avatar` (with `--theme`) shows the signed-in user's initials on `--accent-soft`; `/app` uses it for the account button.
- `.iconbtn--bell` (with `--theme`) is `/app`'s notifications button: a 16px line bell and an `.iconbtn__count` on its top-right corner (`--bad-soft` / `--bad-ink` like `.nav__count.is-alert`, ringed in `--surface`, "9+" past nine, `hidden` at zero). The count is `aria-hidden`; the button's `aria-label` and `title` carry it ("Notifications, 3 unread").
- In the minimal skin (`html[data-layout="minimal"]`, `/app` and `/backoffice`) buttons are 36px with a 9px radius and semibold text: `--primary` / `--accent` is solid yellow with near-black `--accent-ink` text and darkens to `--accent-hover`; `--ghost` is white with a hairline border; `.iconbtn--theme` is a 36×36 rounded square.
- On touch phones (under 560px with `pointer: coarse`) `.btn--sm`, `.chip` and the toolbar `.sel` are 36px tall and the drawer's close `.iconbtn` 40px, so a thumb hits them; the layout around them doesn't change.
- Disabled: `disabled` attribute (opacity 0.5). Do not invent a `--disabled` class.

## Brand

```html
<div class="brand">
  <div class="brand__mark" aria-hidden="true">AI</div>
  <div class="brand__text">
    <div class="brand__name">Name</div>
    <div class="brand__sub">Eyebrow</div>
  </div>
</div>
```

Mark is 40×40, gradient, display font (in the minimal skin a 32×32 solid yellow square). Surfaces: CRM SVG house, app `"AI"`, backoffice `"BO"`. Keep that 2-letter / SVG convention. The backoffice's name and eyebrow come from `backoffice.brandName` / `backoffice.brandSub`.

In `/app` a company that uploaded a logo shows it in the mark's place: `.brand__mark--logo` holding an `img`, as wide as the logo needs up to 104px at the mark's height, on `--doc-page` white with a hairline in both themes, because logos are drawn for the paper they print on. Its 2-letter text comes back when the logo is removed. Settings → Appearance previews the logo in a `.logo-tile` (200×80, the same white; `.logo-tile--empty` is a dashed `--surface-2` box saying there is none yet) beside Upload or Replace and a ghost Remove, in a `.logo-field` row that wraps on phones.

`/app` renders the brand as the company switcher: `button.brand.brand--switch` with `span` children and a `.brand__chevron`. It stays `disabled` (looks exactly like the plain brand, no chevron) until the tenant holds more than one company; enabled, it hovers on `--surface-2` and opens the "Switch company" drawer, a `.record` > `.panel` > `.worklist` of companies (current one `data-tone="ok"` with a "Current" pill). Settings → Companies reuses the same rows.

## Nav

```html
<nav class="nav">
  <div class="nav__group">
    <div class="nav__group-label">Inbox</div>
    <a class="nav__item is-active" data-tab="conversations" href="#conversations">
      <span class="nav__dot"></span>
      <span class="nav__label">Conversations</span>
      <span class="nav__count is-alert">2</span>
    </a>
  </div>
</nav>
```

Wrap related items in `.nav__group`. The first group (Home) may omit `.nav__group-label`. `.nav__count.is-alert` is for waiting chats, overdue invoices, overdue payments, or pending bookings.

Active = `.is-active`. New tabs **must** get a `--nav-icon` outline mask in the minimal block, keyed on `data-tab` (`/app`) or `href` (backoffice: `#tenants`, `#admins`, `#backups`, `#settings`; Settings shares `/app`'s gear). Copy an existing tab block. The classic `--tab` colors and `.nav__dot::after` emoji are the base layer under the skin; no page shows them.

In the minimal skin the active item is a `--accent-soft` fill with a 3px `--accent` bar on the left (`box-shadow: inset 3px 0 0`), `--ink` text at weight 700 and a dark icon; icons follow the label color. Group labels are small sentence-case `--ink-mute` text. Items are 32px tall; the sidebar scrolls when the nav outgrows the window.

### Compact shell: sidebar head and folding

On `/app` (`html[data-shell="compact"]`) the brand shares a `.sidebar__head` with `.sidebar__toggle` (`#btn-sidebar`), which folds the desktop sidebar to a 56px rail of icons: `body.sidebar-collapsed`, kept per browser in `localStorage` `ui:sidebarCollapsed`. Folded, group labels become 1px rules, counts and the foot hide, and each `.nav__label` stays for screen readers (visually hidden) while the link's `title` names it on hover; `renderNav()` sets that `title`. The toggle's label (`app.sidebarCollapse` / `app.sidebarExpand`) and `aria-expanded` are set by `paintSidebarCollapsed()`. Under 920px the toggle hides and the slide-in menu works as before. The sidebar sits on the gray `--bg` with 28px items, a `--nav-active` gray fill for the active one (no yellow bar), and group labels 24px tall.

## Topbar

```html
<header class="topbar">
  <div class="crumb">
    <span class="crumb__root">Root</span>
    <span class="crumb__sep">/</span>
    <span class="crumb__leaf">Leaf</span>
  </div>
  <div class="topbar__search">
    <input type="text" id="search" placeholder="Search…" autocomplete="off" />
    <kbd>/</kbd>
  </div>
  <div class="topbar__actions">
    <button class="iconbtn iconbtn--theme" id="btn-theme" type="button">🌙</button>
    <button class="btn btn--ghost" type="button">Log out</button>
    <button class="btn btn--primary" type="button">New</button>
  </div>
</header>
```

Order: crumb · search · actions. Theme button is always in actions (a `.topbar--one-row` moves it into the menu on phones, below). Below 920px search takes its own row under the crumb and actions.

Neither `/app` nor `/backoffice` has a layout switch: the skin is fixed in markup (`<html data-layout="minimal">`). There, `.topbar__search` draws its magnifier with `::before` (no extra markup), sits on `--bg` with a hairline border and a 10px radius, and focuses with the shared ring. The `/` hint is `aria-hidden`; the input gets its accessible name from `data-i18n-aria-label` (`app.searchPlaceholder`, `backoffice.searchPlaceholder`).

The backoffice only searches tenants, so it sets `hidden` on `.topbar__search` on its other views. `.topbar__actions` sits in the last grid column, so the actions stay at the right edge without it. Signed out, it also hides the menu button (`#btn-nav`), the crumb, Log out and New: only the theme switch stays.

The backoffice's bar is `.topbar.topbar--one-row`: under 920px its search stays on the first row (menu and crumb · search · actions) instead of taking a row of its own, and under 620px it is the menu, the search and New. There the search takes the crumb's place while it shows (the page title below says the same), and the theme switch and Log out move to the end of the menu, in a `.sidebar__session` ([Sidebar KPIs](#sidebar-kpis)); signed out there is no menu, so the theme switch stays in the bar. `/app` keeps the two rows: its bar also holds the bell and the account button.

On `/app` the actions are: theme · notifications bell (`#btn-notifications`) · account (`#btn-account`, `.iconbtn--avatar`) · Log out · New. The bell shows once someone is signed in, operators included; the account button stays `hidden` until the signed-in user is known, and stays hidden when an operator opens the dashboard (no user account). `#btn-new` is `span.btn__plus` + `span.btn__label` ("+ New client"; the backoffice's "+ New bot/client" uses the same markup); below 620px it keeps only the plus, a 36px square, with the label kept for screen readers.

In the compact shell the bar is a 40px page bar on the main card: the crumb's root and separator hide, and `.crumb__leaf` shows the page's nav icon (`render()` sets its `data-tab`, which the `--nav-icon` rules key on, as for `.nav__item`) and name. Its buttons are 28px. Under 560px the icon hides.

## View hero + stats

```html
<div class="view__hero">
  <div>
    <h1 class="view__title">Title</h1>
    <p class="view__desc">One-line description.</p>
  </div>
  <div class="view__stats">
    <div class="stat">
      <span class="stat__label">Label</span>
      <span class="stat__value">12</span>
    </div>
  </div>
</div>
```

Stats cycle personality colors by `nth-child` (accent, mint, coral, sky, sun, grape). Numeric values use `.stat__value`; tinted emphasis uses `.stat__value--accent`. Optional `.stat__hint` under the value is for a vs-last-period delta — add `.delta--up` / `.delta--down` to it when the delta has a direction. `.stat--lg` is a bigger spotlight variant (Home's highlights only); don't use it on list-page stat rows (clients, services, quotes, invoices, catalog).

In the minimal skin `.view__stats` is one white card (hairline border, `--r-lg`, `--shadow-sm`) whose cells are split by inset hairlines; it is a grid of equal columns and drops to two columns under 700px.

A stat can lead with an icon well — pass `icon` in the `statCards` item:

```html
<div class="stat stat--icon">
  <span class="stat__icon" data-icon="users" aria-hidden="true"></span>
  <div class="stat__label">Total</div>
  <div class="stat__value">12</div>
</div>
```

`.stat__icon` is a 36px `--accent-soft` square with a dark outline icon, drawn as a mask from `data-icon` (`users`, `user-plus`). Add a `.stat__icon[data-icon="…"]` rule with a `--stat-icon` SVG before using a new name. Clientes uses it today.

### Hero with a period navigator

When the stats under the hero summarize a single week or month (Services/Invoices in `By week`/`By month` view — [patterns.md](patterns.md)), stack a `.period-nav` above `.view__stats` inside a `.view__hero-right` wrapper so the whole right-hand column — nav and numbers — reads as one unit describing "which period, then its numbers":

```html
<div class="view__hero">
  <div>
    <h1 class="view__title">Invoices</h1>
    <p class="view__desc">Issued invoices and their status.</p>
  </div>
  <div class="view__hero-right">
    <div class="period-nav">
      <button class="btn btn--sm" type="button" aria-label="Previous">‹</button>
      <span class="period-nav__label mono">15 – 21 Sep 2026</span>
      <button class="btn btn--sm" type="button" aria-label="Next">›</button>
      <button class="btn btn--sm" type="button">Current</button>
    </div>
    <div class="view__stats"><!-- stat cards for the selected period --></div>
  </div>
</div>
```

The "Next" button gets `disabled` when the selected period is the current one — there's nothing to move forward to. The trailing "Current" button (`t.periodCurrent`) only renders once the viewer has actually navigated away from today's period; don't show a no-op jump button when already there. `.period-nav__label` reuses the same `periodLabel()` range text as the table's period rows and the row it currently matches gets `.is-current` in the history table below, so the nav, the stat cards, and the highlighted row all agree on the same selected period.

## Health pulse

Home’s status banner under the hero:

```html
<div class="pulse pulse--urgent">
  <span class="pulse__icon" aria-hidden="true">🚨</span>
  <span class="pill pill--bad">Urgent</span>
  <span class="pulse__text">3 items need you · €1.240,00 to collect</span>
</div>
```

Modifiers: `.pulse--ok` `.pulse--watch` `.pulse--urgent`, each tinting `.pulse__icon`'s background too (`ok` → 🟢/✅, `watch` → 👀, `urgent` → 🚨 — pick the emoji in JS from the same health value, it's decorative so it doesn't go through i18n). Dark theme keeps the same semantic tints.

## Snapshot grid

Home’s enabled-module cards, one per operational module. Two columns, one column below 920px:

```html
<div class="home-grid">
  <div class="panel snapshot" data-kind="financeiro">
    <div class="snapshot__head">
      <span class="snapshot__icon" aria-hidden="true">💶</span>
      <div class="snapshot__head-text">
        <h2 class="panel__title">Financeiro</h2>
        <div class="snapshot__figure">€ 75,50</div>
      </div>
      <button class="btn btn--sm snapshot__open" type="button">Open</button>
    </div>
    <div class="meter"><div class="meter__fill" style="width:60%"></div></div>
    <div class="snapshot__metrics">
      <div class="snapshot__metric"><span class="snapshot__metric-label">Received this month</span><span class="snapshot__metric-value">€ 225,50</span></div>
  </div>
</div>
```

- `data-kind` selects the card's accent color (`--snap`), reusing the same personality-tint idea as nav tabs / `.stat` — see the `[data-kind]` rules in `style.css` (`financeiro`, `pipeline`, `customers`, `services`, `suppliers`, `employees`, `inbox`, `contacts`, `calendar`, `social`, `catalog`, `assistant`). Reuse a nav tab's hex when the module has a nav tab; keep unlisted kinds on the `var(--accent)` fallback.
- `.snapshot__icon` is a module emoji, matching its nav dot emoji.
- `.snapshot__figure` is the card's single headline number (display font, large, tinted `--snap`) — not a small `.tag`.
- `.meter` / `.meter__fill` is optional: a thin progress bar for a card whose primary number is naturally a share of a whole (Financeiro's received vs. spent, quote win rate). Omit it for cards without a meaningful ratio.
- The Financeiro card (see [patterns.md](patterns.md#home-tenant-snapshot)) is the one snapshot that folds two data sources (`o.cash`, `o.payments`) into a single simple card — received/spent this month as its headline and meter, receivable/payable as the two secondary numbers. Don't reintroduce a separate "Payments" snapshot card; that would bring back the dense, aging-bucket version this replaced.
- `.snapshot__metrics` is a 2-column grid of secondary numbers (`.snapshot__metric-label` + `.snapshot__metric-value`), not a vertical label/value list. A lone trailing item in an odd-length list spans both columns.
- Do not replace this with a table.

## Panel + chips

```html
<div class="panel">
  <div class="panel__head">
    <h2 class="panel__title">Directory <span class="tag">12</span></h2>
    <div class="panel__tools">
      <select class="sel" aria-label="Filter">
        <option>All</option>
      </select>
      <button class="chip is-on" type="button">All</button>
      <button class="chip" type="button">Open</button>
    </div>
  </div>
  <!-- table or body -->
</div>
```

`.chip.is-on` = selected filter (solid accent). `.tag` inside `.panel__title` is a count pill.
A compact `.sel` may sit in `.panel__tools` for long entity lists (e.g. filter Serviços by client, Pagamentos by supplier). It uses chip height (28px) and `width: auto`; do not drop a full-width form select into the toolbar.
`.panel__tools--chips` (the backoffice's tenant status filter) lays its chips out two to a line across the panel under 560px, centred, instead of letting the last one wrap alone.

`input.inp.panel__search` (`type="search"`) is a compact search field for a panel's tools or filters: 220px wide and as tall as the chips and toolbar selects beside it (28px, 36px on touch phones, 16px text on touch screens), the full width under 560px. The backoffice's Board and Passwords use it; the tenants list keeps the top bar's search. Redraw only what it filters, or put the focus and caret back, so typing isn't interrupted.

`.view__foot` holds secondary actions at the end of a view, right-aligned under its last panel (Passwords' "Change the master password").

In the compact shell the chips in `.panel__tools`, `.panel__filters` and `.panel__views` read as flat view tabs: no border, `--nav-active` gray when chosen. A filter chip may end with `chipCount(n)` (`components.js`, a `.chip__count`) for the records it leads to, as Quotes and Invoices do; only add it where the chip's count matches the rows it shows (not where another filter, a client or supplier select, narrows the list too). The list page's panel (`.view > .panel`) runs edge to edge of the main card with only top and bottom rules, the table is a 32px grid (hairlines between columns), and `.view__hero` shows only its stats, as separate number cards: its heading is visually hidden, since the page bar names the page.

`crmPanel({ title: '' })` draws a head of view tabs only (`.panel__head--tabs`, the page bar names the page); wrap buttons that belong at its end (Columns, Fields) in `.panel__tools-end`. `pageCount(n)` (`components.js`) shows the list's count beside the page name in the bar (`#crumb-count`); `render()` clears it for every page, so a page sets it each render. A hero with no figures is `.view__hero--bare`, and in the compact shell the list under it starts right under the page bar. A list's name cell may lead with initials: `.tbl__who` > `.tbl__avatar` + a `.tbl__open` button (the Clients list).

**Picking rows** (`columns.js`): `selectColumn(table, canPick?)` is a list's first column (pinned, left out of the column picker), its heading a select-all box. `selectionBar(table, actions?)` goes in `crmPanel({ bar })` and shows while a row is ticked: the count (`app.columns.selected`), the page's own buttons, Export CSV and Clear. `wireSelection(root, table, listedIds)` wires it; the ticks live in `state.selectedRows[table]` while the page is open (through searches, filters and redraws, for rows still listed) and clear on a page change. Export CSV writes the ticked rows with the columns as shown, pinned columns aside, semicolon-separated where the language writes decimal commas. A tick box and its cell don't open the row's record. Clients, Quotes, Invoices, Services and Employees have it; Services adds Invoice selected (the ticked open services of one client). Names lead with `nameWithInitials(name, open?)` (`components.js`).

**Record and document drawers** (`records.js`, `components.js`): `recordBarHtml({ name, contact, end })` is the bar under the head (initials, contact buttons, edit and remove at the end), `recordTabsHtml(tabs, current)` the tabs (wire `[data-record-tab]`), `recordFieldsHtml(rows)` the field list (`[label, trusted html, tone?]`, empty rows dropped), `recordFigureHtml(value, note)` a figure in a field and `kpiFieldRows(cells)` KPI cells as fields. A quote, invoice or service drawer is `recordFieldsHtml` (Status, Client via `docClientHtml(name, link)`, dates, money), `docLinesHtml(items, total)` (a "Lines" table with a ruled Total line), notes as `.hint`, then `docFootHtml(buttons)`: the actions pinned to the drawer's foot, links and removal first, the main step (`.btn--primary`) last. A drawer whose `.form` or `.record` ends in a `.drawer__foot` fills the drawer, so the foot sits at the bottom even when the content is short; `mountRecordAutomations()` inserts above that foot. The client and employee records open on Overview / Profile; suppliers keep the record card.

Records: `.record-bar` (avatar, contact buttons, `.record-bar__end` for edit and remove) replaces the record card at the top of the client's drawer, and its Overview tab is a `dl.record-fields` (label and value per line; a KPI's value takes `.record-fields__value`, its note `.record-fields__sub`, and a `data-tone` colors it). In the compact shell a record's `.chip-tabs` read as underlined tabs on one scrolling line.

### Panel with a view switch (List / By week / By month)

When a panel has a control that swaps its whole layout (columns, hero, even the stat row) rather than just narrowing the rows, keep it out of `.panel__tools` — a `.chip` reads as "one of several filters," which undersells a control that changes the table's shape. Use `crmPanel`'s `views` slot instead of `tools`; it renders in the head as its own `.panel__views` group (same buttons the bookings toolbar uses: `.btn.btn--sm`, active = `.btn--primary`), and any ordinary filters passed via `tools` automatically drop to a `.panel__filters` strip underneath, on a `--surface-2` band with its own bottom border:

```html
<div class="panel">
  <div class="panel__head">
    <h2 class="panel__title">Serviços <span class="tag">12</span></h2>
    <div class="panel__views">
      <button class="btn btn--sm btn--primary" type="button">List</button>
      <button class="btn btn--sm" type="button">By week</button>
      <button class="btn btn--sm" type="button">By month</button>
    </div>
  </div>
  <div class="panel__filters">
    <select class="sel" aria-label="Client"><option>All clients</option></select>
    <button class="chip is-on" type="button">All</button>
    <button class="chip" type="button">Open</button>
  </div>
  <!-- table -->
</div>
```

Used by Services, Invoices, and Financeiro (`design-system/patterns.md`). Don't reuse `.panel__views` for anything that is itself a filter (status, client, supplier) — those stay `.chip`s in `.panel__tools`/`.panel__filters`.

## Table

```html
<div class="tbl-wrap">
  <table class="tbl">
    <thead>
      <tr>
        <th>Name</th>
        <th class="right">Total</th>
        <th></th>
      </tr>
    </thead>
    <tbody>
      <tr class="is-overdue">
        <td class="name">Acme</td>
        <td class="num">€ 120,00</td>
        <td class="actions">
          <button class="btn btn--sm btn--accent" type="button">Pay</button>
          <button class="iconbtn" type="button">×</button>
        </td>
      </tr>
    </tbody>
  </table>
</div>
```

Cell helpers: `.name` `.id` `.muted` `.num` `.mono` `.right` `.actions` `.nowrap` `.long`.

A table can have more columns than its panel fits, as the `/app` directory lists can once someone adds columns; it then scrolls inside `.tbl-wrap`, and the browser squeezes every cell that may wrap down to its longest word. So values that must stay whole take `.nowrap` (phone numbers, NIFs, postal codes, dates, numbers), and text that wraps takes `.long`, an 11em minimum, so a name or an address breaks over a few lines rather than one word per line. `.id` codes take neither (they still break at the hyphen on a phone).

A row that opens a record (the backoffice tenants) puts its name in `button.tbl__open`, which looks like the text and takes the focus ring, and listens for clicks on the whole row, skipping its other buttons; such rows get the pointer cursor. Keyboards reach the record through the name. Several pills in one cell stack in `.tbl__pills`, one per line, so the column is only as wide as the widest pill. A link inside a cell's text (a punch's place on the map) is `a.tbl__link`: `--info-ink`, underlined, with the focus ring.

A table that has to read on a phone takes `.tbl--stack` (every backoffice table does, and `/app`'s Timesheets and Persona sources and history) instead of scrolling sideways. Under 920px, where the sidebar is a menu, the head is hidden and each row becomes a card. A cell with `data-label` (its column heading, from the same catalog key as the `th`) shows that label above its value, and those cells share a grid of four columns, two under 560px. A cell without `data-label` spans the card: the row's name comes first, its actions last (left-aligned), and an empty state sits in the `colspan` cell. A long `.id` (a file name) and a `.tbl__wide` cell (the tenants' channels) span too, keeping their label; an empty cell (no action on this row) disappears, and numbers align left.

A status that matters at a glance sits beside the name: the name cell's first line is a `.tbl__title` holding the name and the same pill with `.tbl__aside`, which only shows in the card (at its top right), and the status column's cell takes `.tbl__stack-hide`, so the card leaves it out. The backoffice's tenants, dashboard users and agents do this.

`.tbl--flow` (with `.tbl--stack`; the backoffice's short lists) lets a card's labelled values follow at their own width instead of the grid, wrapping like words, puts a time `.sub` on its date's line and pills side by side, and closes the last line with the actions, at its right end when they fit. A tenant then takes about 230px at 375px instead of 320px. Keep the grid for tables whose cards are compared value by value (shifts, hours), where it lines the values up.

```html
<table class="tbl tbl--stack tbl--flow">
  <thead><tr><th>Name</th><th>Channels</th><th>Status</th><th class="right">Msgs</th><th class="right">Actions</th></tr></thead>
  <tbody>
    <tr>
      <td class="name"><div class="tbl__title"><button class="tbl__open" type="button">Padaria Central</button><span class="pill pill--ok tbl__aside">Active</span></div><div class="sub mono">padaria-central</div></td>
      <td class="tbl__wide" data-label="Channels"><div class="tbl__pills"><span class="pill pill--info">WhatsApp</span></div></td>
      <td class="tbl__stack-hide" data-label="Status"><span class="pill pill--ok">Active</span></td>
      <td class="num" data-label="Msgs">12</td>
      <td class="right"><div class="actions"><button class="btn btn--sm" type="button">Open dashboard</button></div></td>
    </tr>
  </tbody>
</table>
```

In the minimal skin the list page's directory card — the `.panel` straight under `.view` — uses 20px side padding for its head, filters and cells (16px under 920px), a 58px head and a lightly tinted `--surface-2` header row. Panels inside drawers and Home cards keep the compact 16px spacing.

Row markers (left inset bar): `.is-overdue` (bad), `.is-paid` (ok), `.is-draft` (faint), `.is-current` (accent, the period that contains today). `.is-total` is the compiled totals row (surface background, heavier weight). `.is-day` is a full-width group header row (one `td colspan`) for date-grouped lists such as the bookings agenda.

**Movable columns.** On `/app`'s list tables (Clients, Suppliers, Employees, Services, Quotes, Invoices, Financeiro, Payments, Catalog, the bookings agenda, My services) each person drags a heading sideways to move its column, and ← and → move a heading that has the focus. Build such a table from column definitions (`{ id, label, head, pin, cell }`, beside the page: `quoteColumns()`…) with `app/columns.js`: `orderedColumns()` puts them in the person's order, `columnsHead(columns, table)` gives each movable `th` `data-col`, `tabindex="0"` and a `title` saying how (`app.columns.moveHint`), and `wireColumnOrder(root, table, columns)` after render wires it. A row's tick box (`pin: 'start'`) and its actions (`pin: 'end'`) keep their place. A movable heading shows the grab cursor and, focused, an inset `--focus-border` edge (`.tbl-wrap` would clip an outer ring). While one drags, its column's cells take `.is-col-dragged` (the `--accent-soft` selection tint) and the column it will land beside takes `.is-drop-before` or `.is-drop-after`, a 2px `--ink` line down the table. A mouse drags at once; a finger or pen holds the heading first, so a swipe still scrolls the table. Once someone has moved a list's columns, a ghost **Restore the column order** `.btn--sm` (`app.columns.resetOrder`) joins its tools, after the filters (after Columns on the directories, before Services' Invoice selected). Week and month summaries, record tabs and the backoffice keep fixed columns.

## Empty

```html
<div class="empty">
  <p class="empty__title">Nothing here</p>
  <p class="empty__desc">Create the first item to get started.</p>
</div>
```

Place inside a table cell with `colspan`, or in a panel body. The generic glyph is CSS (`::before`: a wand in classic, a line tray in the minimal skin).

A module may replace it with its own small line illustration: `crmPanel({ …, emptyArt })` renders `div.empty.empty--art` with the inline SVG first:

```html
<div class="empty empty--art">
  <svg class="empty__art" viewBox="0 0 128 84" aria-hidden="true" focusable="false">
    <path class="art-accent" d="…"/>          <!-- sparkles and small fills: --accent -->
    <g class="art-line"><path d="…"/></g>     <!-- strokes: currentColor (--ink) -->
  </svg>
  <p class="empty__title">No clients found</p>
  <p class="empty__desc">Adjust the search or create a new client.</p>
</div>
```

Keep it minimal and professional: outline strokes, at most a few accent shapes, no emoji or photos. Colors come only from `.art-line` / `.art-accent`, so dark mode works without extra rules. Only Clientes has one today (`CLIENTS_EMPTY_ART` in `app/clients.js`: a client, a spray bottle, three sparkles).

## Pills

```html
<span class="pill pill--ok">Paid</span>
<span class="pill pill--warn">Pending</span>
<span class="pill pill--bad">Overdue</span>
<span class="pill pill--info">Sent</span>
<span class="pill pill--accent">Draft</span>
```

Map domain status → these five tones. Do not create `pill--purple`. PDF links use `.pdf` / `.pdf--ghost`, not pills.

## Forms

```html
<form class="form">
  <div class="form__grid">
    <div class="form__row">
      <label class="lbl" for="name">Name <span class="req">*</span></label>
      <input class="inp" id="name" required />
    </div>
    <div class="form__row">
      <label class="lbl" for="kind">Type</label>
      <select class="sel" id="kind"><option>Service</option></select>
    </div>
    <div class="form__row form__row--full">
      <label class="lbl" for="notes">Notes <span class="opt">optional</span></label>
      <textarea class="txt" id="notes"></textarea>
      <p class="hint">Helper text.</p>
    </div>
  </div>
</form>
```

- Grid: `.form__grid` (2 col), `.form__grid--3` (3 col), `.form__row--full` spans. Both are one column under 560px.
- Fields: `.inp` `.sel` `.txt`. Money/IDs: `.inp--mono` `.inp--right`.
- Labels: `.lbl` (sentence case in the minimal skin; uppercase only in the classic layer). Required: `.req`. Optional: `.opt`.
- Hints: `.hint`; `.hint--warn` for a warning, `.hint--bad` for an error (add `role="alert"` when it appears after an action).
- A single yes/no option inside a form: `<label class="form__check"><input type="checkbox" /> Label</label>` (accent-colored box, same size as `.tbl td.check`). Don't build a toggle switch.
- Several options in one field (the backoffice's tenant modules): `.form__check` labels inside `div.form__checks` (`role="group"`, `aria-labelledby` the field label), a grid of 140px+ columns that fills the width (three in a drawer, two on a phone). Longer labels (a supplier's usual services with their price, in the payment form) take `.form__checks--wide`, with 220px+ columns and one column on a phone. A disabled box, for an option that is always on, turns its label `--ink-mute` with a not-allowed cursor. On touch phones a label is 32px tall and the grid's row gap 4px, so the whole line takes the tap. Don't use pills as checkboxes.
- A field label with its own tools on the same line (All / None, Add channel): `.form__row-head` > `.lbl` + `.actions`.
- Several short values typed into one field (a Persona menu's keywords): `div.tokens` holding one `.token` per value (`.token__text`, a button that takes the value back to edit, and `.token__rm` ×) and a bare `input.tokens__input` last, which carries the field's `id` for the label. It looks like `.inp`, grows in rows, and rings on `:focus-within`. `.token--warn` marks a value with a problem (its `title` says which); `.tokens.is-readonly` has no input and no ×. Enter adds, a pasted list adds a value per line; don't split on commas, since a value may contain one.
- A field a refused save points at gets `aria-invalid` (red border on `.inp`, `.txt` and `.tokens`, a red inset in a `.line`; the focus ring still wins) and a `.hint--bad` under it saying what is wrong, until it's right.
- `button.linkbtn`: a button that reads as a link inside a sentence or hint ("Show", "From 1 · Prices", "Hide map"): `--ink-2` text with a hairline underline, the focus ring. Not for actions that change data.
- A field with its own buttons on its line (a saved password's Show, Generate and Copy): `div.inp-row` > `.inp` + `.btn--sm`s. The field takes the room left; under 560px the buttons wrap under it.

### Save bar

A long form whose changes are saved together (the Persona Menus tab, the backoffice's Platform settings) ends with a `.savebar`:

```html
<div class="savebar is-dirty">
  <p class="savebar__status" aria-live="polite"><span class="savebar__dot" aria-hidden="true"></span>Unsaved changes · 2 to fix</p>
  <div class="actions"><button class="btn btn--ghost" type="button">Discard changes</button><button class="btn btn--primary" type="submit">Save</button></div>
</div>
```

It rests at the end of the form; with `.is-dirty` it is `position: sticky` 16px above the bottom of the screen, so Save stays in reach while scrolling, and toasts rise above it (`html:has(.savebar.is-dirty) .toast`). Clean, its status is muted and both buttons are `disabled`. Discard confirms through `.confirm`. On phones the buttons share the full width.

### Suggestions under a field

Type-ahead matches for an existing record (e.g. a CRM client under a booking's name/phone):

```html
<div class="form__row suggest-host">
  <label class="lbl" for="bk-name">Contact name</label>
  <input class="inp" id="bk-name" autocomplete="off" />
  <div class="suggest">
    <button class="suggest__item" type="button"><strong>Ana Silva</strong><span class="mono">+351 912 345 678</span></button>
  </div>
</div>
```

`.suggest` floats under its `.suggest-host` row (`hidden` when empty). Keep focus in the input on `mousedown` so a click still picks the row.

A list long enough to scroll takes `.suggest--scroll`. Headings inside it are `.suggest__group`, and “nothing matched” is `.suggest__empty`. The entry the arrow keys are on carries `aria-selected="true"`, which highlights it like `:hover`.

## Line items

Use `.lines` / `.lines__head` / `.line` / `.lines__foot` for quote, invoice, payment and Serviços editors (`lineItemsField()` in `app/billing.js`), wrapped in `.lines-field` with the section `.lbl`. Numeric inputs get `.num`. Remove button: `.l-rm`. Do not replace this with a generic table. Under 560px each line stacks: the description cell (`.line__desc`) takes its own row and quantity, unit and price share the next one; the header follows the same grid.

The description cell is also the catalog picker, so it is a `.line__desc` wrapper (input + `.line__caret` + a `.suggest` dropdown as `.line__opts`) rather than a bare input, and it carries the focus ring for the whole cell. The dropdown has to escape the box, so a `.lines` that holds one takes `.lines--combo`, which drops `overflow: hidden` and moves the rounded corners onto the head and foot. A `.lines` without a combo keeps clipping as before.

The backoffice's tenant channels reuse the box as `.lines--channels`: platform select, external id, access token and `.l-rm`, with left-aligned headings. Under 560px a channel takes two lines, platform and external id over the access token, with remove beside both; the head follows the same grid, so "Access token" sits on its second line. A `.sel` inside a `.line` sits flush like the row's bare inputs (no border, radius or shadow; the cell takes the focus fill). Inputs in a row are bare `input`s (`.mono` for ids), not `.inp`, and carry an `aria-label` because the heading isn't their label. An empty list shows `.lines__empty` under the head (`hidden` hides it). In the minimal skin `.lines__head` is sentence case like table heads.

More variants keep the channels' bare inputs with `aria-label`s, without a catalog combobox:

- `.lines--services`: a supplier's usual services, as description, unit, usual price and `.l-rm`. On phones the description takes its own row, like `.line__desc`.
- `.lines--breaks`: a shift's breaks in the Timesheets correction form, as from and to (`input type="time"`) and `.l-rm`, left-aligned headings, same columns on phones.
- `.lines--replies`: a Persona menu message's buttons, as title (a bare `input` with the 20-character `maxlength`), "Leads to" (a flush `.sel` of the menu's messages plus "New message…"), `.l-go` (→, goes to that message; disabled until one is chosen) and `.l-rm`; four columns on phones too. Its foot holds "Add button" and the count; an empty list says the menu ends there.
- `.lines--installments`: an invoice's installments, as a read-only number in `span.line__index`, amount (`.num`), due date (`input type="date"`) and `.l-rm`; the date heading is left-aligned. Same four columns on phones. Its foot holds the add button and the total, or what is still to split.
- `.lines--columns`: a backoffice board's columns in its settings, as name (a bare `input`), done (a checkbox centred in `label.line__check`), move up and down (two `.l-move` ↑ ↓ in `span.line__moves`) and `.l-rm`. Same four columns on phones. Its foot holds "Add column".

```html
<div class="line">
  <div class="line__desc">
    <input data-k="description" role="combobox" aria-expanded="false" aria-autocomplete="list" aria-controls="line-opts-1" />
    <button class="line__caret" type="button" tabindex="-1" aria-label="Show catalog">▾</button>
    <div class="suggest suggest--scroll line__opts" id="line-opts-1" role="listbox" hidden></div>
  </div>
  <input class="num" data-k="quantity" /><input class="num" data-k="unit" /><input class="num" data-k="unitPriceEur" />
  <button class="l-rm" type="button">×</button>
</div>
```

The foot holds one button that appends an empty row, and the total. Do not put a second catalog `.sel` + Add button back there: picking happens in the row being filled.

## Drawer

Shell is in each `index.html`. JS fills title + body and appends `.drawer__foot`:

```html
<div class="drawer" id="drawer" hidden>
  <div class="drawer__scrim" data-close></div>
  <aside class="drawer__panel" role="dialog" aria-modal="true">
    <header class="drawer__head">
      <div>
        <div class="drawer__eyebrow">Eyebrow</div>
        <h2 class="drawer__title" id="drawer-title">Title</h2>
      </div>
      <button class="iconbtn" data-close aria-label="Close">×</button>
    </header>
    <div class="drawer__body" id="drawer-body"></div>
  </aside>
</div>
```

Wide editor: `.drawer__panel--wide`. Footer:

```html
<div class="drawer__foot">
  <button class="btn btn--ghost" data-close>Cancel</button>
  <button class="btn btn--accent" id="drawer-save">Save</button>
</div>
```

The footer is sticky and is the last thing in the body, directly or inside the drawer's `<form>`. A `.drawer__body` that holds one drops its bottom padding: sticky offsets are measured inside the scroller's padding, so with it the stuck footer would cover the end of the content when scrolled to the bottom.

Show/hide with the `hidden` attribute, not a CSS class. On open: focus the first control, trap Tab inside the panel, Escape closes, and restore focus to the opener.

Under 920px the panel takes the full width with square corners; under 560px its head, body and footer use 16px side padding. On touch phones the close button is 40px and the `.drawer__back` link keeps its look with a 32px hit area.

`openDrawer(title, body, wide, { eyebrow })` sets the eyebrow text (default "Dashboard"). A drawer opened from another drawer (a record, an invoice…) through `openFrom(back, open)` stacks its opener on a trail and replaces the eyebrow with `.drawer__back` (the opener's name; the ← glyph is CSS). Back pops one step, and a drawer that closes itself after an action (save, mark paid, convert) reopens its opener with fresh data. Closing by hand (×, scrim, Escape) clears the trail and leaves.

The backoffice keeps one level: `openDrawer({ …, back: { label, open } })` puts the same `.drawer__back` in the eyebrow, and a save reopens `back` instead of closing (`onSave` may also return false to stay, or the next drawer to open). Without `onSave` there is no footer. Without `back` the eyebrow reads `eyebrow` (the board's name and the card's key, "Passwords"), or "Tenant". On a touch screen (`pointer: coarse`) it focuses × instead of the first field, so the keyboard doesn't open over the drawer. A long name in `.drawer__back` wraps left-aligned, the ← on its first line.

A drawer that edits a record ends its body with `p.hint.drawer__meta` (who created and last changed it, and when) and `div.actions.drawer__actions`, the secondary actions under a hairline (Archive, a `.btn--danger` Delete), before the sticky footer. Destructive ones still confirm through `.confirm`.

```html
<div class="drawer__eyebrow"><button class="drawer__back" type="button" aria-label="Back to Ana Ribeiro">Ana Ribeiro</button></div>
```

In a detail drawer (quote, invoice, payment, booking), the name in `.detail__head` becomes a `button.detail__client.detail__client--link` (accent, trailing →) that opens that client's or payee's record, unless the trail already leads back there.

## Record

Clients, suppliers and employees (and the backoffice's tenants, [patterns.md](patterns.md#tenants-backoffice-directory--record)) open as a record, not a form ([patterns.md](patterns.md#clients-directory--record)):

```html
<div class="record">
  <section class="record-card">
    <div class="record-card__head">
      <span class="record-card__avatar" aria-hidden="true">AR</span>
      <div class="record-card__who">
        <div class="record-card__lines">
          <span class="record-card__line mono">+351 911 222 333</span>
          <a class="record-card__line" href="mailto:ana@example.pt">ana@example.pt</a>
        </div>
        <p class="record-card__since">Client since 28/09/26 · 2 visits · last activity yesterday</p>
      </div>
      <div class="actions">
        <button class="btn btn--sm btn--ghost" type="button">Edit</button>
        <button class="btn btn--sm btn--ghost" type="button">Delete</button>
      </div>
    </div>
    <div class="record-card__contact"><a class="btn btn--sm" href="tel:+351911222333">Call</a></div>
    <div class="record-card__notes"><span class="record-card__notes-label">Notes</span>Prefers afternoons.</div>
  </section>
  <div class="record-kpis" data-count="4">
    <div class="record-kpi" data-tone="bad">
      <span class="record-kpi__label">Outstanding</span>
      <span class="record-kpi__value">€ 140,00</span>
      <span class="record-kpi__sub">€ 95,00 overdue</span>
    </div>
  </div>
  <section class="panel"><header class="panel__head"><h2 class="panel__title">Needs attention <span class="tag">3</span></h2></header><ul class="worklist">…</ul></section>
  <div class="chip-tabs" role="tablist">
    <button class="chip is-on" type="button" role="tab">Activity</button>
    <button class="chip" type="button" role="tab">Invoices<span class="chip__count">3</span></button>
  </div>
  <div class="record__pane" role="tabpanel">…</div>
  <div class="drawer__foot record__foot"><button class="btn btn--sm" type="button"><span class="btn__plus">+</span> New booking</button></div>
</div>
```

- `.record-kpis` holds up to four `.record-kpi`; `data-count` sets the columns (two below 560px). `data-tone="bad|warn|info"` colors the value. Values sit at the bottom of the cell so they line up when a label wraps.
- The attention list and the client's Activity tab are `.worklist` rows (`.worklist__item` buttons). `data-tone` works inside `.dash`, `.record` and any `.worklist` (a drawer `.form`, the Agents panes), on the row or on its dot. `.worklist__group` is a group header row (Upcoming, History), styled like `tr.is-day`. `.worklist--wrap` lets titles and details wrap (keeping line breaks) instead of truncating, for rows that carry a sentence someone wrote (notifications); the dot stays on the first line. A `disabled` row keeps its look but loses the hover and pointer (e.g. Google in the account drawer when Google sign-in isn't configured).
- `.chip-tabs` is a row of `.chip` buttons that switches sections inside one surface; `.chip__count` is the number inside a chip. A supplier or employee has one list (payments), so no tabs; its table panel carries a `.panel__head` title instead.
- Tables reuse `.panel` + `.tbl` (compact cell padding inside the record) with the usual row markers, or `.empty` with a create button.
- A pane with more than one block (the client's Financeiro tab: `.record-kpis`, a `.hint`, the movements table, a `.record__pane-foot`) wraps them in `.record__stack` for even spacing. `.record__pane-foot` holds one or more right-aligned buttons.
- The create actions are a sticky `.drawer__foot` at the end of the record. A foot action that creates nothing (the employee's Print report) is a `.btn--ghost` without the `+`.
- A report printed from the browser (`printHtmlDocument` in `app/payees.js`) is a standalone HTML document laid out in a hidden `.print-frame` iframe. It carries its own small print stylesheet (A4 `@page`, greys on white, no theme or skin), the way a PDF has its own look, so the dashboard tokens don't apply inside it.
- The card head's buttons sit in one `.actions` row (Edit, then Delete, or Restore on an archived record); below 560px the row drops under the name. Delete confirms through `.confirm`; when documents refer to the record, the confirm offers Archive instead (primary button, not danger).
- An archived record shows a `.hint--warn` line in the card ("Archived on …") and no create actions (neither the foot nor the empty-pane buttons) until it is restored. A client whose automations are paused gets the same kind of line ("Automations are paused for this client").
- With Agents on, the client record ends its tabs with **Automations** (count = runs in progress + open tasks), drawn by `AgentsUI.renderAutomations`: a `.panel` whose head carries a ghost "Pause automations" in `.panel__tools`, an optional `.panel__filters` row (agent `.sel` + "Run now") for agents run by hand on clients, then one `.worklist` with In progress / Open tasks / Recent `.worklist__group` rows, or `.empty`. Paused, a `.notice--warn` with Resume sits above the panel and the run row goes away. Quote, invoice and booking details append the same block below `.detail__foot` (`mountRecordAutomations`), hidden while it has nothing to show.
- The Clients, Suppliers and Employees lists carry an Active / Archived pair of `.chip` buttons in their `.panel__head` tools.
- `.hint--warn` is a warning-colored hint, e.g. "another client already uses this phone" under the client form's phone field.
- Form sections toggled with the `hidden` attribute (the payment form's supplier / employee fields, the catalog's booking fields) rely on `.form__row[hidden]` and `.form__grid[hidden]`; a class that sets `display` otherwise wins over the attribute and the section stays visible.

## Confirm

```html
<div class="confirm" id="confirm" hidden>
  <div class="confirm__scrim" data-confirm-cancel></div>
  <div class="confirm__panel" role="alertdialog" aria-modal="true">
    <h3 class="confirm__title">Confirm</h3>
    <p class="confirm__body">This cannot be undone.</p>
    <div class="confirm__actions">
      <button class="btn btn--ghost" data-confirm-cancel>Cancel</button>
      <button class="btn btn--danger" id="confirm-ok">Confirm</button>
    </div>
  </div>
</div>
```

## Toast

```html
<div class="toast" id="toast" hidden>
  <span class="toast__dot"></span>
  <span>Saved</span>
</div>
```

One toast node per page. JS pattern: set innerHTML, `hidden = false`, auto-hide ~2800ms. Do not stack toasts. Under 560px a long toast wraps at the screen's width less 16px a side; centred from `left: 50%`, it would otherwise wrap at half the screen. While a `.savebar` follows the screen, toasts sit above it, or they would cover its Save button.

## Auth card

```html
<div class="auth">
  <div class="auth__card">
    <div class="auth__mark">AI</div>
    <p class="auth__eyebrow">Tenant</p>
    <h1 class="auth__title">Sign in</h1>
    <p class="auth__desc">Email and password.</p>
    <form class="form" id="login-form">
      <div class="form__row">
        <label class="lbl" for="email">Email</label>
        <input class="inp" id="email" type="email" autocomplete="email" required />
      </div>
      <button class="btn btn--primary" type="submit">Continue</button>
    </form>
  </div>
</div>
```

Render login **inside** `#view` so the sidebar/topbar chrome can remain or clear as each app already does. Submit button is full-width (`.auth .btn`).

A view's own lock screen, under the shell's top bar, is the same card in `.auth.auth--view`, which drops the full-screen height for a little top padding: the backoffice's Passwords creates and unlocks the vault with it. Its `.auth__mark` may hold a line icon (an `svg` drawn in `currentColor`) instead of letters, and a `.notice--warn` under `.auth__desc` carries what the person must know before going on (nobody can recover the master password).

When Google sign-in is configured (`/admin/auth/config`, `/app/auth/config`), a `.btn--primary` "Continue with Google" button comes first, then an `.auth__desc` line ("or sign in with your email and password"), then the form with a `.btn--ghost` submit. A refusal that needs explaining (no linked user, a different Google account) shows under the Google button as `.hint.hint--warn` with `role="alert"`; cancelled or failed popups use the toast.

## Chat (persona / thread / assistant)

| Class | Role |
|---|---|
| `.chat__log` | Scrollable transcript |
| `.chat__msg chat__msg--user` | Outgoing (gradient) |
| `.chat__msg chat__msg--bot` | Incoming (surface + border) |
| `.chat__typing` | Italic muted |
| `.chat__note` | A centered muted line about the chat, not in it: where the persona test would hand over, an error |
| `.chat__form` + `.chat__input` | Composer (pill input) |
| `.assistant` | Two-column assistant shell |
| `.assistant__thread` / `.is-active` | Thread list item: title, relative time, and a `.pill--warn.assistant__pending` count of the changes waiting in it |
| `.assistant__search` | Conversation search over the list, once there are more than four (and while a search is on) |
| `.assistant__head` | Above the log: `.assistant__title`, Rename and Delete; a plain `.pill` "Answers only" while changes are off |
| `.assistant__welcome` + `.assistant__starters` | An empty conversation: a title, a line and starter questions as `.btn.btn--sm` that send at once |
| `.assistant__turn` + `.assistant__meta` | A bot answer and the line under it: "Looked at: …" and the `.assistant__copy` link |
| `.chat__msg--rich` | A bot bubble drawn from a markdown subset after escaping: paragraphs, lists, `.chat__heading`, `code`, http(s) links, and `.chat__table`, which scrolls inside the bubble with numbers right-aligned (`td.num`) |
| `.assistant__failed` | A turn without an answer (`--bad-soft` bubble), with "Try again" in `.assistant__meta` when it is the last message |
| `.assistant__action` (`--pending` `--confirmed` `--failed` `--cancelled` `--expired`) | Confirm-before-execute card: `.assistant__action-head` (label, title, status pill), `.assistant__action-details`, `.assistant__quote` for a reply's exact text, a `.hint` on expired and failed ones, `.assistant__action-buttons` |
| `.assistant__composer-hint` | Under the composer: the Enter / Shift+Enter hint and, near the limit, a character count |

User bubbles use the gradient; bot bubbles use surface + hairline. Do not invert that. The customer inbox has its own components (next section).

### Persona studio

```html
<div class="persona-studio">
  <div class="persona-studio__main">
    <div class="settings-tabs" role="tablist">…Behavior · Knowledge · Instructions · Menus · History…</div>
    <div class="persona-studio__pane" role="tabpanel"><!-- panels --></div>
  </div>
  <aside class="persona-studio__test">
    <section class="panel"><!-- head: title, .pill--warn "Unsaved changes" (hidden when none), Clear; body: .chip-picks, .chat__log, .chat__form --></section>
  </aside>
</div>
```

Two columns (editor, test chat at up to 400px) with the chat sticky at the top; one column under 1100px, the chat after the editor. The main column and its pane are grids with a 16px gap, so panels inside need no margins. Its "Unsaved changes" pill toggles with `hidden` (`.persona-studio .pill[hidden]`).

#### Menus tab (`app/menus.js`)

```html
<form class="form flow-menus">
  <section class="panel"><!-- head: title + count .tag, saved-by .panel__meta; body: hint, .record-kpis, .form__grid (fallback .sel, "Check a message" .inp + result .hint) --></section>
  <section class="panel flow-menu is-open">
    <header class="panel__head flow-menu__head">
      <h2 class="flow-menu__title"><button class="flow-menu__toggle" aria-expanded="true"><span class="flow-menu__chevron"></span><span class="flow__index">1</span><span class="flow-menu__name">Welcome menu</span></button></h2>
      <span class="flow-menu__pills"><span class="pill pill--ok">On</span><span class="pill pill--warn">2 to fix</span></span>
      <div class="panel__tools"><!-- Try it, ↑ ↓ --></div>
      <p class="flow-menu__summary">Hello · Hi · Good morning +2 · 7 messages · Sent 191 times to 41 customers in the last 30 days</p>
    </header>
    <div class="panel__body form flow-menu__body"><!-- name, state, .tokens keywords, .flow-menu__notes, the map, .flow.flow--edit cards, .flow-menu__foot --></div>
  </section>
  <div class="savebar">…</div>
</form>
```

| Class | Role |
|---|---|
| `.flow-menu` (`.is-open`) | One menu, a `.panel` that opens in place. The whole head toggles it (a click outside its buttons), keyboards use `.flow-menu__toggle` inside the `h2`, with `aria-expanded`. Folded, the head loses its bottom rule and the body (`[hidden]`, which needs `.flow-menu__body[hidden]` over `.form`'s grid) isn't drawn |
| `__chevron` `__name` `__pills` `__summary` | Turning chevron, the position (`.flow__index`: the first menu with a matching keyword answers), the name, On/Off plus a `.pill--warn` "n to fix" or "n to check", and a muted line of keywords, size and 30-day usage (refusals in `.hint--warn`), under the name |
| `__notes` · `__stat` · `__foot` | Lines under the keywords (a repeat refused, a clash in `.hint--warn`); "Sent n times" in a message card's head; the body's last row: Add message on the left, Duplicate and Remove menu on the right |
| `.flow-map` > `__tree` / `__row` / `__list` > `__branch` | The menu's map: from message 1, each button (`__btn`, a quick-reply pill with →) and the message it leads to (`__msg`, a button with `.flow__index` and `__text`) on one `__row`, nested `__list`s drawn as a tree with hairlines. A message reached again is `__back` + `__msg--ref`; `__end` (gives way first on narrow screens) and `__note` (leads nowhere, `--warn-ink`) end a row; `__orphans` lists unreachable messages. Only the text shortens; a very deep tree scrolls inside the box |
| `.flow__card.is-flash` | The message a map node, a From link or → just went to, ringed for a moment |

Rules: the map, summaries and notes are redrawn as the draft changes, but never a field being typed in, and only when their HTML changed, so a button under the pointer isn't swapped out. Problems under fields show only after a refused save; the head's count is always live.

## Conversations inbox

`/app` → Conversations. A fixed-height `.inbox` grid: `.inbox__list` (filters + rows) and `.inbox__thread` (head, banners, log, composer). Both scroll inside; the page does not. Under 920px it is one column and `data-thread-open` on `.inbox` shows the list or the thread.

```html
<div class="inbox" data-thread-open="true">
  <aside class="inbox__list">
    <div class="inbox__list-head">
      <div class="inbox__list-top"><select class="sel inbox__asset">…</select><button class="btn btn--primary btn--sm inbox__new">New message</button></div>
      <div class="inbox__filters"><button class="chip is-on">All</button><button class="chip">Needs reply<span class="chip__count">2</span></button></div>
    </div>
    <ul class="inbox__rows" role="list">
      <li><button class="inbox-row is-active is-unread is-waiting" aria-current="true" aria-label="Ana Silva · 2 unread · …">
        <span class="inbox-avatar">AS</span>
        <span class="inbox-row__main">
          <span class="inbox-row__top"><span class="inbox-row__name">Ana Silva</span><time class="inbox-row__time">5 min</time></span>
          <span class="inbox-row__bottom"><span class="inbox-row__preview"><span class="tick tick--read">…</span>Até amanhã</span><span class="inbox-row__flag">AI paused</span><span class="inbox-row__badge">2</span></span>
        </span>
      </button></li>
    </ul>
  </aside>
  <section class="inbox__thread">
    <header class="thread-head">back · avatar · <div class="thread-head__who">name + sub</div> <div class="thread-head__tools">pills + AI button</div></header>
    <div class="thread-banner thread-banner--warn">…Resume AI</div>
    <div class="thread-log" role="log"><div class="thread-day"><span>Today</span></div><article class="bubble bubble--in bubble--customer">…</article></div>
    <button class="thread-jump" hidden>New messages</button>
    <footer class="composer">…</footer>
  </section>
</div>
```

| Class | Role |
|---|---|
| `.inbox-row` `.is-active` `.is-unread` `.is-waiting` | List row. Active = `--accent-soft` fill; unread = heavier text; waiting = `--warn` dot on the avatar (also in the row's `aria-label`) |
| `.inbox-row__badge` / `.inbox-row__flag` | Unread count (accent fill) / "AI paused" (warn tint) |
| `.inbox-avatar` (`--lg`) | Initials on `--accent-soft` (text `--accent-deep`, `--accent` on dark) |
| `.chip__count` | Small mono count inside a filter chip |
| `.thread-head` `__back` `__who` `__name` `__sub` `__tools` | Thread header; `__back` only under 920px |
| `.thread-banner` (`--warn`) `__text` `__meta` | Full-width notice under the header: read-only website chat, AI paused (with Resume) |
| `.thread-log` / `.thread-day` / `.thread-skeleton` | Transcript on `--surface-2` (`--bg-deep` dark), day separators, loading bars |
| `.thread-jump` | "New messages" pill floating over the log's bottom edge when messages arrive while scrolled up |
| `.bubble` `--in` `--out` + `--customer` `--agent` `--ai` `--automation`, `.is-pending` `.is-failed` | Message. Customer: surface + hairline, left. Agent (a person): `--accent-soft`, right. AI: surface + dashed hairline, right. Automation (an agent from the Agents module): `--info-soft`, right, its `__author` in `--info-ink` led by a `__tag` pill ("Automation") and the agent's name; the list row's preview starts "Automation:" like "AI:". Parts: `__author` (`__tag`) `__text` (pre-wrap) `__media` `__meta` `__error` |
| `.tick` `--pending` `--sent` `--delivered` `--read` `--failed` | Delivery state as an inline SVG with `role="img"` + `aria-label`; read = `--info`, failed = `--bad`. Only for messages with a WhatsApp id |
| `.composer` `__form` `__input` `__actions` `__foot` `__counter` `__error` `__notice` `__notice-text` | Reply box: auto-growing textarea, Template + Send, hint and counter (`.hint`, toned with `--warn` / `--bad`); `__notice` replaces the form when WhatsApp's 24-hour window is closed or the channel is gone |
| `.inbox-ico` | 16px line icon (inline SVG, `currentColor`) inside buttons |
| `.wa-send` `.wa-params` `.wa-param` `.wa-preview` (`__buttons` `__footer`) `.wa-var` | Template drawer: variables and a live preview bubble; unfilled variables show as `.wa-var`. `.wa-send__manage` sits left in the foot. WhatsApp template UI uses `wa-`; `tpl-` belongs to the PDF document studio |
| `.bubble__media-box` (`.is-expanded`) with `.bubble__media-status` (`.is-error`), `.bubble__image-btn` + `.bubble__image`, `.bubble__audio`, `.bubble__video`, `.bubble__file` (`__file-action`) | Customer media inside a bubble: a dashed placeholder while it loads, then the photo (click toggles `.is-expanded`), a native player, or a download row for documents. Links in `.bubble__text` use `--info-ink`, underlined |
| `.wa-templates` (`__body` `__table` `__excerpt` `__reason`) | Settings → WhatsApp templates: a `.panel` whose table shows the body excerpt under the name and the rejection reason under a `.pill--bad` status |
| `.wa-editor` `__grid` `__preview` `__tools` `__buttons` `__button` `__add` | New-template drawer (`drawer__panel--wide`): fields beside a sticky preview column; one column under 760px |

Rules: status and time lines are mono; everything else is sans. Media is fetched with the bearer token and shown from a blob URL; only JPEG/PNG/WebP/GIF, audio and video render inline, anything else downloads. The window pill is `.pill--ok` (open), `.pill--warn` (under 2 h left) or plain `.pill` (closed). Under 620px the composer buttons become icon-only and keep their label for screen readers.

## Email page

`/app` → Email (`app/email.js`). The same `.inbox` grid and thread parts as Conversations (`.inbox__list`, `.inbox-row`, `.thread-head`, `.thread-log`, `.composer`), with email cards instead of bubbles and a "What to do" panel at the top of the thread. A setup `.notice` (connect Gmail, reconnect, turn on reading, first check) sits in `.email-setup` above the grid and hides under 920px while a thread is open.

```html
<li><button class="inbox-row email-row is-unread" data-em-thread="…">
  <span class="inbox-avatar">MS</span>
  <span class="inbox-row__main">
    <span class="inbox-row__top"><span class="email-row__who"><span class="inbox-row__name">Maria Silva</span><span class="email-row__count">3</span></span><time class="inbox-row__time">5 min</time></span>
    <span class="email-row__subject">Pedido de orçamento</span>
    <span class="inbox-row__bottom"><span class="inbox-row__preview">Bom dia, …</span><span class="email-row__intent">Quote request</span><span class="inbox-row__badge">1</span></span>
  </span>
</button></li>

<section class="email-actions">
  <header class="email-actions__head"><h3 class="email-actions__title">What to do</h3><span class="pill pill--info">Quote request</span><span class="email-actions__tools"><button class="btn btn--sm btn--ghost">Read again</button></span></header>
  <div class="email-actions__summary"><span class="inbox-ico">…</span><div><p>Summary</p><p class="hint">Read by AI…</p></div></div>
  <ul class="email-actions__list">
    <li class="email-action is-primary"><span class="email-action__icon">…</span><span class="email-action__main"><span class="email-action__title">Add Maria Silva as a client</span><span class="email-action__detail">maria@… · 912 345 678</span></span><span class="email-action__side"><button class="iconbtn email-action__dismiss">×</button><button class="btn btn--sm btn--primary">Add client</button></span></li>
    <li class="email-action is-done">… <span class="pill pill--ok">Done</span><button class="btn btn--sm btn--ghost">Open</button></li>
  </ul>
  <div class="email-actions__docs"><span class="email-actions__docs-label">Mentioned in this email</span><button class="chip">FAT-001 · Pending · 615,00 €</button></div>
</section>

<details class="email-msg email-msg--out" open>
  <summary class="email-msg__head"><span class="email-msg__from"><strong>Obras Lopes</strong> <span class="email-msg__addr">obras@…</span></span><span class="email-msg__side"><span class="pill pill--info">Sent</span><time class="email-msg__date">…</time></span><span class="email-msg__peek">snippet, shown while folded</span></summary>
  <p class="email-msg__meta">To … · Sent by …</p>
  <div class="email-msg__body">…</div>
  <p class="email-msg__files"><span class="inbox-ico">…</span><span>orcamento.pdf</span></p>
</details>
```

| Class | Role |
|---|---|
| `.email-row` (`.is-automated`) + `__who` `__count` `__subject` `__intent` | A thread row: the name with a message-count badge (hairline pill, mono, kept apart from the time), the subject line under it, the model's intent as an `--info-soft` tag. Automatic mail (newsletters, notifications) gets a muted avatar |
| `.email-thread` | The thread log with a wider gap between cards |
| `.email-actions` `__head` `__title` `__tools` `__summary` `__reading` `__list` `__docs` `__docs-label` | The "What to do" card: intent pill and Read again / Suggest actions in the head, the model's summary on `--surface-2` with its spark icon in `--accent-deep` (`--accent` on dark), a pulsing "Reading…" line while it reads, the suggestions, and chips for the quotes and invoices the email names |
| `.email-action` (`.is-primary` `.is-done`) + `__icon` `__main` `__title` `__detail` `__side` `__dismiss` | One suggestion: icon well on `--accent-soft`, a title and what its form starts with, then Not needed (`.iconbtn`) and the action (`.btn--primary` when primary). Primary rows get the strong hairline; done rows are dashed with an `--ok-soft` icon, a `.pill--ok` and Open. Under 560px the buttons drop under the text |
| `.email-msg` (`--out`) + `__head` `__from` `__addr` `__side` `__date` `__peek` `__meta` `__body` `__files` | An email as a native `<details>` card: older ones fold to their head and a one-line peek, the newest (and unread ones) start open. The body is escaped plain text, `pre-wrap` |
| `.email-reply` `__head` `__to` `__row` `__input` | The composer as a column: "Reply to …" with Use the suggested reply, then the textarea (grows to 240px) and Send. Ctrl/Cmd+Enter sends; Enter is a new line |

Rules: everything an email says is escaped text, never HTML. The dismiss button's `aria-label` names the action it hides. The model's intent and summary are labelled as AI's reading; forms opened from a suggestion still validate as they always do.

## Work queue

Home uses `.queue` / `.setup-list` of `.queue__item` buttons: a leading `.queue__icon` (emoji, decorative), a title + detail block, then an optional trailing `.queue__meta` column with a status pill or timestamp:

```html
<button class="queue__item" type="button">
  <span class="queue__icon queue__icon--warn" aria-hidden="true">💬</span>
  <div><strong>Waiting on you</strong><span>Ana Silva · 12 min</span></div>
  <span class="queue__meta"><span class="pill pill--warn">Waiting</span></span>
</button>
```

`.queue__icon` tones: `--warn` `--bad` `--info` `--accent` (match the item's pill tone), or the plain `--surface-2` fill for neutral setup items. Click navigates to the matching module. Do not replace this with a table.

## Meter

Thin progress bar for a card whose headline number is a share of a whole (win rate, collected vs. outstanding):

```html
<div class="meter"><div class="meter__fill" style="width:62%"></div></div>
```

`.meter__fill` width is a JS-computed percentage (clamp 0–100), not a token. Inside `.snapshot`, its fill color follows that card's `--snap` tint automatically. Only add a meter where the ratio is meaningful — most panels don't need one.

Settings → Home reuses `.queue__item.choice` in a `.choice-list`. Visible cards get `.is-on` (accent border) plus a Shown/Hidden pill. Do not invent a second toggle primitive. Settings → Appearance uses the same list for Theme (light / dark), in a `.panel` with `.panel__body`.

## Card panel

A `.panel` whose content is not a table:

```html
<section class="panel panel--card">
  <header class="panel__head">
    <h2 class="panel__title">Cash flow</h2>
    <div class="panel__tools"><span class="panel__meta">Last 6 months</span><button class="btn btn--sm btn--ghost" type="button">Open →</button></div>
  </header>
  <div class="panel__body">…</div>
</section>
```

- `.panel__body` pads the content; `.panel__body--flush` drops the padding for edge-to-edge row lists.
- `.panel__meta` is muted head text (period, count) next to the tools.
- `.panel--card` (minimal Home) removes the head divider and makes the ghost "Open →" link borderless. Inside a padded card body, `.worklist` / `.rank` bleed to the card edges automatically.

## Minimal Home (dashboard)

Building blocks of the minimal-layout Home ([patterns.md](patterns.md#home-minimal-layout)). All live under `.dash`, read tokens only, and take a semantic tone through `data-tone="accent|ok|info|warn|late|bad|neutral|muted"` (sets `--tone`) instead of per-color classes.

| Block | Role |
|---|---|
| `.dash__head` · `.dash__date` · `.dash__title` · `.dash__status--ok\|watch\|urgent` (+ `.dash__dot`, `.dash__sep`) · `.dash__actions` | Date eyebrow, greeting, health line, quick-create buttons |
| `.dash-kpis` > `button.dash-kpi` (`__label` `__value` `__value--warn` `__meta` `__meta--bad`, optional `.spark`) | One hairline strip of up to 5 KPIs; cells share 1px grid gaps; one row on desktop, two columns under 700px (a lone last cell spans) |
| `.dash-grid` > `.dash-col` × 2 (`.dash-grid--single`) | Wide main + side column; under 1100px the columns become `display: contents` and cards sort by `data-order` |
| `.worklist` > `li` > `button.worklist__item` (`__dot` `__main` `__title` `__detail` `__side` `__meta` `__meta--amount` `__when`) | Needs-you queue, activity feed, overdue rows. `data-go` + optional `data-open` deep-links to a record |
| `.agenda` > `.agenda__item` (`__time` `__rail` `__main` `__who` `__what`, `.is-past` / `.is-now`) | Today's bookings on a timeline rail |
| `.bars` (`__scale` `__plot` `__pair` `__bar` `__labels` `__label.is-current`) | Grouped month columns; bar height is an inline `%` like `.meter__fill`; `role="img"` + a text `aria-label` |
| `svg.spark` (`__line` `__area`, `.spark--tall`) | Trend line in the accent color, built in JS |
| `.segbar` > `.segbar__seg` + `.legend` / `.legend--rows` (`__item` `__swatch` `__value`) | Receivables aging split and its key |
| `.hbars` > `.hbars__row` (`__label` `__track` `__fill` `__value`), `.hbars--wide` for names | Pipeline stages, work by employee |
| `.insights` (`__group` `__group--end` `__label` `__range` `__hint` `__table` `__split`) · `.dash-figures--4` | Service insights card: a `.panel__views` switch, period and rank-by chip groups in `.panel__filters`, four figures with a `.delta` in each label, bleeding `.tbl--stack` tables |
| `.cellbar` > `.cellbar__fill` | Thin share bar under a table cell's name (inline `%` width) |
| `table.heat` > `td.heat__cell` (`--heat: 0–100%`, `.is-hot` above 55%) | Who-buys-what heat map: accent mixed into `--surface-2`, so it works in both themes; under 560px only four service columns show |
| `.rank` > `button.rank__item` (`__pos` `__name` `__value` `__bar` `__fill` `__fill--billed` `__meta`) | Top clients, billed vs paid |
| `.dash-split` · `.dash-figures` · `.dash-figure` (`--lg` `--end`, `__value--bad\|warn`, `__label`) | Headline numbers inside a card |
| `dl.dash-facts` (`--row`, `--stack`) · `.dash-sub` · `.dash-empty` (`--center`) · `.dash-axis` | Key/value rows (a long value wraps; `--stack`, for long values like a tenant's channels, puts each label above its value under 560px), sub-heading, quiet empty line, sparkline axis |
| `.dash-tiles` > `button.dash-tile` (`__label` `__value` `__meta`) | Small module tiles for modules without a card |

Do not reuse these as classic Home components, and do not add emoji to them — the minimal layout is emoji-free.

## Media thumb

Used by the Instagram inbox for post previews:

```html
<img class="ig-thumb" src="…" alt="" />
<div class="ig-thumb ig-thumb--empty" aria-hidden="true">◇</div>
```

48×48, `object-fit: cover`, `--r-md`. Empty placeholder uses `--surface-2` + `--ink-faint`. Comment threads in the drawer use `.ig-post` + `.ig-comments` / `.ig-comment` (`.is-target` for the comment that opened the drawer).

Services tables may use a leading `.tbl td.check` column for multi-select before invoicing. Do not invent a new checkbox primitive.

## Settings tabs + widget customizer

```html
<div class="settings-tabs">
  <button type="button" class="chip is-on">Home</button>
  <button type="button" class="chip">Channels</button>
  <button type="button" class="chip">Website</button>
</div>
<div class="panel widget-customizer">
  <div class="widget-customizer__studio">
    <form class="form widget-customizer__controls">…</form>
    <div class="widget-preview">
      <div class="widget-preview__stage">
        <div class="widget-demo widget-demo--light widget-demo--right">…</div>
      </div>
    </div>
  </div>
</div>
```

`.settings-tabs` is a chip row, not a second nav. `.widget-customizer` pairs localized form controls with a responsive `.widget-demo` preview; update its `--widget-demo-accent` and `--widget-demo-accent-ink` properties from the color input. The generated embed snippet is the saved deliverable, while allowed origins remain a separate server-side security setting.

The isolated embed under `resources/widget/` accepts `data-title`, `data-subtitle`, `data-welcome`, `data-placeholder`, `data-launcher`, `data-accent`, `data-position`, and `data-theme`. Dashboard classes must not be copied into the embed.

## Bookings calendar

The Bookings page is one panel: a head of view tabs (Week / List; in List the status tabs with counts after a `.panel__sep`) with the week's ‹ › (and "This week" once moved), Services and Opening hours in `.panel__tools-end`; the page bar shows the week's range, or the List's count. Do not introduce a third-party calendar skin.

```html
<div class="week-board">
  <section class="week-board__day is-today" aria-label="Tuesday, 27 October">
    <h3 class="week-board__head"><b>27</b>Tue</h3>
    <button class="week-event week-event--confirmed" type="button" data-booking-open="…"><b>Ana Silva</b><span>10:00 · Consulta</span></button>
    <button class="week-board__add" type="button" data-booking-slot="2026-10-27T09:00" aria-label="New booking on …">+</button>
  </section>
  <section class="week-board__day is-closed">…</section>
</div>
```

- Seven day columns of cards (one column under 900px). A card's 2px left edge and tint carry its status: `.week-event--pending` (`--warn`), `--confirmed` (`--accent` fill), `--completed` (`--ok` edge), `--no-show` (`--bad` edge, faded), `--cancelled` (faded, name struck through). No raw hex.
- `.is-today` puts the date in a yellow badge; `.is-closed` hatches a day without opening hours. Each day ends in `.week-board__add`, which books it at its first opening hour (shown on hover and focus, always on touch screens).
- Day keys and prefilled times are **tenant-local** (`Tenant.timezone`), never the browser zone.
- List: `agendaColumns()` (tick box, when with its day, contact with initials, phone, service, duration, status, price, then Confirm or the source pinned at the end), no day group rows.
- The booking drawer is a document drawer: `recordFieldsHtml` (status, contact, phone, when, service, duration, price, source, billing, notes), then `docFootHtml` with the step that moves it on (Confirm, Mark done or Invoice) last.
- Opening-hours editor rows: `.booking-avail` > `.booking-avail-row` (day `.sel`, from/to `.inp`, remove `.iconbtn--danger`); `.booking-avail-row--head` carries the column labels.
- Free-time picker in the booking form: `.slot-picks` of `.chip` buttons (picked = `.is-on`).
- `.booking-toolbar`, `.cal-grid` and `.cal-event` (the old hour grid) are left in the stylesheet but nothing draws them since 2026-10-27.

## Document template studio

Quote/invoice PDF designer in Dashboard → Settings. One A4 page, not a second stylesheet.

```html
<div class="panel tpl">
  <div class="tpl__head">…</div>
  <div class="tpl__studio">
    <div class="tpl__pane">…layers…</div>
    <div class="tpl__stage">
      <div class="tpl__sizer"><div class="tpl__page is-decor">
        <div class="tpl__block is-on" data-block="logo">…</div>
      </div></div>
    </div>
    <div class="tpl__pane tpl__inspect">…</div>
  </div>
</div>
```

- `.tpl__page` is 595×842 CSS px (1pt = 1px), scaled with `--tpl-scale`. Paper tokens (`--doc-page*`) stay white in dark theme.
- Blocks are absolutely positioned. Selection: `.is-on`. Hidden: `.is-off`. Resize: `.tpl__handle--nw|n|ne|e|se|s|sw|w`.
- Preview chrome inside the page (`--doc-brand`, `.tpl-kicker`, `.tpl-table`, `.tpl-total`) is document ink, not dashboard `--ink`. `doc-template.js` sets `--doc-brand-ink` (ink or white on an accent fill) and `--doc-brand-text` (the accent darkened to 4.5:1 for labels) with the same formulas as `PdfGenerator`; never color preview text with raw `--doc-brand`.
- Blocks carry `.tpl__block--<id>` (studio and thumbnails) for per-block styling. `data-block` is studio-only: `applyGeometry` finds blocks with `host.querySelector('[data-block=…]')`, so a thumbnail carrying it would be moved instead.
- Templates opens a **wide** drawer. `.tpl-presets` is a card grid of A4 thumbnails (`.tpl-thumb` + `.tpl-preset`), not a swatch list. Built-in designs live in `BuiltInDesignTemplates`.
- `.tpl__page[data-style="plain|split|band"]` paints the readable table (qty/price columns, no pills). Classic keeps its own chrome: accent header bar, rounded rows, total pill, letter-spaced labels. Do not restyle Classic to look like Clear.
- Do not replace this with a generic drawer form.

## Settings rows

```html
<div class="settings-stack">
  <div class="settings-row">
    <div>
      <div class="settings-row__key">feature.flag</div>
      <div class="settings-row__meta"><span class="pill pill--ok">live</span></div>
    </div>
    <div class="settings-row__controls"><input class="inp" /></div>
    <div class="settings-row__actions"><button class="btn btn--sm btn--ghost">Reveal</button></div>
  </div>
  <div class="savebar">…</div>
</div>
```

Three columns (key and pills · field · buttons) on wide screens. Under 860px the key shares its line with its pills, and a lone button (Reveal, Clear override) sits beside the field; two buttons take their own line, and an empty `.settings-row__actions` takes none. The backoffice's Platform settings save every changed row at once from a `.savebar` at the end of the stack (Save, and Discard through `.confirm`); the hero keeps only Reload.

## Email (Google)

Gmail shows up in five places, all built from existing parts ([patterns.md](patterns.md#email-google)); strings live under `app.integrations.google.*`, and the Email page's under `app.email.*` (its classes: [Email page](#email-page)).

- **Settings → Channels**: one "Email (Google)" row per connected account in the channels `.tbl`: the account in `.mono`, the status as text when it can send, `.pill--warn` when it needs reconnecting, `.pill--bad` when access was removed, and `.pill--info` Default once there are two accounts. While the platform lets companies read inboxes, a connected account that automations read gets `.pill--accent` "Reads the inbox" after its status, or `.pill--warn` "Inbox paused" once a check failed. Actions: Reconnect (`.btn--primary`, admins), Manage (admins and operators), and a ghost "Add account" on the last row. With no account the row offers Connect, or a muted "a company admin connects it". The row is hidden while the platform has no Google OAuth client.
- **Account drawer** (Settings): a `.notice--warn` with Reconnect while the account can't send (a test email Google refuses reopens the drawer in that state), a `dl.dash-facts` (account, connected by, sent today of the daily limit), the Inbox panel below, then the form: sender name, reply-to, signature (`.txt`) and, with several accounts, a `.form__check` "Send from this account by default". `.actions`: Save, Send a test email (ghost), Disconnect (ghost; the confirm carries `.btn--danger`).
- **Send by email** (quote and invoice `.detail__foot`): a drawer form with To, Cc, Subject, Message (`.txt`), a `.form__check` for the PDF (its file name in the label) and a `.hint` naming the sending account (and, for a pending quote, that sending marks it sent). A client without an email gets a `.hint--warn` under To. When nothing can send, a `.notice--warn` names the sending account that needs reconnecting (or says none is connected yet), with Open Settings, and Send stays disabled.
- **Client record → Emails**: a `.worklist` of messages, newest first (dot `info` sent, `accent` received; title: subject; detail: recipient · attachments · snippet; when: relative date), or `.empty`. A row opens the message drawer, titled with the subject: `.detail__head` (sender name + direction pill), `dl.dash-facts` (from address, to, cc, date, sent by), the text in `.email-body` (or a `.hint` once the retention period dropped it) and "Open the quote / invoice" and, with the `email` module, "Open in Email" in `.detail__foot`.
- **Email page** (Inbox group, `email` module): the company's threads, replies and the actions each email calls for ([Email page](#email-page)). With the module on, the Inbox panel's hint says new mail shows there (`inbox.hintEmailPage`).

```html
<div class="email-body">Olá Ana,

Segue em anexo o orçamento ORC-001.</div>
```

`.email-body` is an email's plain text in the reading font with its line breaks kept (`--surface-2`, `--line-soft`, scrolls past 420px). Escape the text. Not for script output (`.log-tail`) or chat (`.bubble`).

A `.notice` placed straight in a drawer `.form` (like in `.record` and `.builder`) drops its bottom margin; the form's gap spaces it.

The account drawer's **Inbox panel** shows only while the platform lets companies read inboxes:

```html
<section class="panel">
  <header class="panel__head"><h2 class="panel__title">Inbox</h2><span class="pill pill--ok">In use</span></header>
  <div class="panel__body form">
    <p class="view__desc">Automations read this inbox. Last checked 4 min. ago.</p>
    <p class="hint">Automations read the new emails that arrive here…</p>
    <div class="actions"><button class="btn btn--sm btn--ghost" type="button" data-ga-inbox="off">Stop using it</button></div>
  </div>
</section>
```

The head's pill is the state: plain Off, `.pill--ok` In use, `.pill--warn` Paused (the account needs reconnecting, or the last check failed). The status line (`.view__desc`: off, first check still to come, or last checked as a relative time) gives way to a `.hint--warn` from `inbox.errors.*` when a check failed. Then a `.hint` on what automations do with the mail and how long it's kept, and `.actions`: "Use in automations" (`.btn--primary`, admins, while the account is connected), "Allow reading" (`.btn--primary`, admins, once Google no longer lets the app read) and "Stop using it" (ghost, anyone who manages the account). An operator sees a muted "a company admin turns this on" instead of the first. These buttons act at once, not on Save, so this isn't a `.form__check`: turning reading on may need Google's consent, and that popup has to open straight from the click.

## Log tail

```html
<pre class="log-tail">[backup-mongo] Dumping…
open no-such-compose.yml: no such file or directory</pre>
```

The end of a script's output (the backoffice's failed backup), monospace on `--surface-2`, wraps long lines and scrolls past 280px. Escape the text. Not for anything a user types.

## Agents

Building blocks of `/app` → Agents ([patterns.md](patterns.md#agents-automations)). Script: `app/agents.js`. Tokens only, emoji-free.

| Block | Role |
|---|---|
| `.notice` (`--warn` `--info`) > `__text` (`strong` + `span`, or `ul.notice__list`) + `__actions` | Inline banner in a view, record or drawer: agents paused, problems to fix before activating, the "let it act on its own" suggestion, the builder's intro on a drafted agent, the backoffice agents drawer's pause state with Pause or Resume, the backoffice's setup warnings (Google sign-in not configured, backup runner stopped) at the top of a `.settings-stack`. Under 560px its buttons go under the text. Not a toast, not a modal |
| `.agent-icon` (`--lg`) | 32px (44px) line icon on the accent tint; the SVG comes from the agent's `icon` key |
| `.agent-cell` (`__text` `__name` `__sub`) | Agents table first column: icon, name, and a `.recipe` under it |
| `.recipe` > `.recipe__part` (`--when`) + `.recipe__arrow` | "When → step → step" chips; the trigger part is tinted; long parts truncate |
| `ol.flow` > `li.flow__step` (`--trigger`) + `li.flow__connector` | Read-only steps (agent overview, run, test run): `.flow__index` (number, or the bolt for the trigger) · `.flow__main` (`__eyebrow` `__title` `__detail` `__detail--bad`) · `.flow__side` (pills). `data-state="done\|failed\|waiting\|skipped"` tints a run step's index |
| `.flow--edit` > `.flow__card` (`__card-head` `__card-summary` `__card-tools`) | Builder cards, one per trigger or step: the type/action select in the head, the schema-driven `.form__grid` below, a `div.flow__connector` between steps |
| `.builder` > `section.panel.builder__section` · `.sel--inline` | Builder drawer sections (When, Only if, Steps, Stop rules, Rules, Voice); "Add …" selects are inline, not full width |
| `.var-tools` (`__select`) · `.var-chip` (`--static`) | Suggested `{{variables}}` as mono chips under a template field, plus a grouped select; `--static` shows a variable inside read-only text |
| `.conds` > `.cond-row` (`--exit`) | Condition rows: field · operator · value · remove `.iconbtn`; with two or more, a `.sel--inline` match select sits on top |
| `.chip-picks` | Multi-select chips (weekdays, statuses, an AI task's actions); picked = `.chip.is-on` + `aria-pressed`. "Describe it" reuses the row for its examples, which fill the textarea instead of staying picked |
| `.ai-outputs` > `.ai-output` (`__desc` `__options`) | An AI task's fields to return, one row each: mono name, type select, what it should contain, remove `.iconbtn` (not on the last row), and a full-width options input under a choice. On narrow screens the description drops to its own line. A ghost "Add a field" button ends the list |
| `.persona-agents` | Mount point at the end of the Persona page's Behavior tab: a `.panel` with a `.tbl` with a `td.check` "Follows it" column, `.agent-cell` and status pill; a `.hint--warn` while the Persona is empty |
| `.inp-unit` (`__label`) · `.inp-range` | A number with its unit (localized with `Intl`), and a from–to pair of time inputs |
| `.gallery` > `button.gallery__card` (`--blank`, `.is-unavailable`) with `__head` `__title` `__desc` `__recipe` `__meta` | Template cards; unavailable ones stay clickable so the setup drawer can say what's missing. `--blank` cards ("Describe it", "Start from scratch") come first and have no recipe |
| `.plain-list` · `.agents-settings` · `.empty__actions` | A bulleted rules list, the Settings tab's two-panel stack, a button row inside `.empty` |

Rules: status pills use tones, not new colors (agent: `--ok` active, `--warn` paused; run: `--info` in progress, `--warn` awaiting approval, `--ok` done, `--bad` failed or needs review). Trigger, action, event, field and reason labels come from `app.agents.*` keys built from server keys (dots become underscores), never from server text; what a finished run did reads from `app.agents.did.*` ("Sent a WhatsApp message"), falling back to the action label. An approval shows the drafted message in a `.wa-preview` bubble and its details in `dl.dash-facts`; nothing is sent from the list. An AI task's fields are named by the user: their labels come from `app.agents.outputs.<name>` when there is one (summary, intent…), else the name as typed. Field names are normalized on change (accents dropped, anything else becomes `_`) so they stay usable as `{{steps.<id>.output.<name>}}`.

A `dl.dash-facts` placed straight in a `.panel__body` drops its top margin and its first row's rule, so no second line runs just under the panel head's border (Agents Settings, the backoffice agents drawer).

## Time clock

The punch card on an employee's **My hours** ([patterns.md](patterns.md#my-hours-the-employees-time-clock)). Script: `app/timesheets.js`.

```html
<section class="panel clock" data-state="WORKING">
  <div class="clock__head"><h2 class="panel__title">Time clock</h2><span class="pill pill--ok">Working</span></div>
  <p class="clock__time">3 h 12 min</p>
  <p class="clock__line">Working since 08:02</p>
  <p class="clock__meta">Last: Clock in 08:02 · At Escritório Lisboa · ±8 m</p>
  <div class="clock__actions">
    <button class="btn btn--ghost" type="button">Start break</button>
    <button class="btn btn--primary" type="button">Clock out</button>
  </div>
  <p class="hint">Your location is read only when you clock in or out, never in between.</p>
</section>
```

- `data-state` is `OFF`, `WORKING` or `ON_BREAK`; `ON_BREAK` turns `.clock__time` to `--warn-ink`. Off, `.clock__time` is the company's current time; on, the time worked so far (tabular figures, ticking).
- `.clock__actions` holds the buttons that fit the state, the main one `.btn--primary`. On phones they share the row.
- A `.notice` inside the card sits flush (the "use the app" notice when the company requires the phone's biometrics, with the buttons `disabled`).
- A forgotten clock-out is a `.notice--warn` above the card whose `form.notice__actions.clock__fix` holds a `.lbl`, a 120px time `.inp` and Save; on phones the notice stacks.

## Backoffice board

The backoffice's Board ([patterns.md](patterns.md#board-backoffice)): one `.panel.board` holding the board's chips, its filters and its columns.

```html
<div class="panel board">
  <div class="panel__head"><div class="chip-tabs" role="tablist">…one .chip per board…</div><div class="panel__tools">…</div></div>
  <div class="panel__filters"><input class="inp panel__search" type="search" /> <select class="sel">…</select></div>
  <div class="board__body">
    <div class="board__cols">
      <section class="board-col">
        <header class="board-col__head"><h2 class="board-col__title">In progress</h2><span class="tag">2</span></header>
        <div class="board-col__cards">
          <article class="board-card" tabindex="0">
            <div class="board-card__top"><span class="board-card__key">OPS-12</span><span class="pill pill--bad">Urgent</span></div>
            <p class="board-card__title">Renew the domain</p>
            <p class="board-card__desc">The .pt renewal is due on the 19th; the card is on Ana's account.</p>
            <div class="board-card__labels"><span class="board-card__label">billing</span></div>
            <div class="board-card__meta"><span class="board-card__fact is-late"><svg>…</svg>19 Oct</span><span class="board-card__avatar">AN</span></div>
          </article>
        </div>
        <div class="board-col__foot"><button class="btn btn--sm btn--ghost board-col__add" type="button">+ Add a card</button></div>
      </section>
    </div>
  </div>
</div>
```

- **Columns** (`.board-col`, a `--surface-2` well) sit side by side in `.board__cols` and scroll inside the panel when they don't fit; under 700px they stack, so nothing scrolls sideways. A column whose cards count as done shows `.board-col__done` (a check in `--ok-ink`, its meaning in `title`) before its count. Counts beside a column's title are `.tag`s styled like a panel title's.
- **A card** is `article.board-card` (`tabindex="0"`): its key in mono, a priority pill (Low `--info`, High `--warn`, Urgent `--bad`; Medium none), its title, the start of its description (`.board-card__desc`, `--ink-2`, line breaks kept, cut after four lines), labels as neutral `.board-card__label`s (not pills, whose tones mean status), and `.board-card__meta` facts with 14px line icons in `currentColor`: due date (`.is-late` in `--bad-ink` once past and not in a done column), checklist progress (`.is-done` in `--ok-ink` when complete), comments, the tenant, and the assignee's initials (`.board-card__avatar`, `--accent-soft` with `--accent-deep` text) pushed to the right. No emoji.
- **Dragging:** the card takes `.is-dragging` (fixed above the page, `--shadow-lg`, tilted 1.5°, `pointer-events: none`, its left, top and width set inline as it moves), `.board-card--slot` (a dashed `--hairline-strong` outline of its height) marks where it will land, the target column takes `.is-drop` (`--accent-soft`), and `.board.is-dragging` shows the grabbing cursor. A mouse drags at once; a finger or pen holds the card first, so a swipe still scrolls; Escape cancels. Alt with an arrow key moves a focused card.
- **Quick add:** "+ Add a card" turns the foot into `form.board-col__new` (an `.inp` and Add / Cancel); Enter adds and keeps the field for the next card, Escape closes it.
- **The card's drawer** puts the checklist and the comments under the form, each a `.board-detail__section` with a `.board-detail__title` (and its `.tag` count). The checklist is a `.meter` (filled `--ok`) over `ul.checklist` of `.form__check` items with a `.checklist__rm` ×, then `form.checklist__add`; a done item's text is struck through in `--ink-mute`. Comments are `ul.comments` of `.comment` (`.comment__head` with the author's initials, name and time, and Delete for the author's own; `.comment__body` keeps line breaks), then `form.comment-form`.

## Building

The Building pages' parts ([patterns.md](patterns.md#building-projects-and-their-progress)). Script: `app/building.js`, which the five pages and My sites share.

```html
<!-- Progress: the work done as a fill in the project's tone, a tick where the plan is by the end of yesterday. -->
<div class="progress-cell">
  <div class="progress" role="img" aria-label="47% done, the plan expects 67%">
    <span class="progress__fill" data-tone="warn" style="width:47%"></span>
    <span class="progress__plan" style="left:67%"></span>
  </div>
  <span class="progress-cell__label"><strong>47%</strong> · plan 67%</span>
</div>
<span class="schedule--behind">6 days behind</span>

<!-- Timeline: one row per project or phase across whole months, today in red. -->
<div class="gantt" role="table" aria-label="Project">
  <div class="gantt__head"><span class="gantt__head-label">Project</span><div class="gantt__scale"><span class="gantt__tick" style="left:0%">Oct 26</span>…</div></div>
  <div class="gantt__row">
    <button class="gantt__label" type="button" data-gantt-open="…"><span class="gantt__name">OB-001 · Moradia</span><span class="gantt__sub">At risk · 47%</span></button>
    <div class="gantt__track"><span class="gantt__line" style="left:33%"></span><span class="gantt__bar" data-tone="warn" style="left:3%;width:64%"><span class="gantt__done" style="width:47%"></span></span><span class="gantt__now" style="left:52%"></span></div>
  </div>
</div>

<!-- Photos: square thumbnails; the ones the client doesn't see carry a pill. -->
<div class="photo-grid">
  <figure class="photo">
    <button class="photo__open" type="button" data-photo-open="…" aria-label="Façade"><img class="photo__img" data-photo="…" data-size="thumb" alt="" loading="lazy" /></button>
    <span class="pill photo__flag">Team only</span>
    <figcaption class="photo__caption">Façade</figcaption>
  </figure>
</div>
```

- **Tone** (`data-tone` on `.progress__fill` and `.gantt__bar`) follows the project's health: `ok` on track or done, `warn` at risk, `bad` delayed, `neutral` planning, `muted` on hold or cancelled. A phase's follows its state: `ok` done, `accent` in progress, `bad` late, `neutral` not started. The tones are the shared `:is(.dash, .record, .worklist, .progress, .gantt) [data-tone]` mapping, never a colour of their own. Behind and ahead read in `--bad-ink` / `--ok-ink`; on plan is plain text.
- `.progress` is 8px (12px on the client's page) on `--bg-deep`; the plan tick is a 2px `--ink` mark that stands out of the bar, so "behind" reads without colour. Its `aria-label` says both numbers. In a table use `.progress-cell` (bar over the label).
- `.gantt` is `bldPanel`'s body for the Timeline view and the project record's Phases tab: the bar spans planned start to end on a `color-mix` of the tone, `.gantt__done` fills the share done, `.gantt__now` marks today. A row without dates says so in its track (`.gantt__undated`). Labels are buttons when the row opens something. Under 560px the label column narrows; nothing scrolls sideways.
- Photos come from the API as blobs (`apiBlob`, the token in a header), so `img.photo__img` starts without `src` and `hydratePhotos()` fills it; an image without `src` stays hidden. Opening one shows `.photo-view` (contained, at most 62vh) in a drawer with its caption, the shared switch and Delete through `.confirm`.
- `.panel__text` is text someone wrote (a diary's work done, an issue's description): 13.5px, line breaks kept, long words wrapped.
- Typed values in a table row (a measurement's quantity to date and %): `.tbl__input` (104px, 32px tall; `.tbl__input--pct` 76px) in a `td.tbl__cell-input`, mono and right-aligned like the numbers around them.
- The phases editor is a `.lines.lines--phases` (name, planned start, planned end, weight, progress, remove), like the line items editor; under 560px the name takes the whole row and weight and progress move under the dates.
- **Nav:** the Building group's counts are delayed projects (alert) or the running ones, active sites without today's diary, open issues (overdue ones as an alert), changes waiting for the client and measurements to invoice. Its tabs have their own outline masks in the minimal block.
- **Costs tab** (project record): a `.panel` with two `.dash-facts--row` (current contract, costs, expected margin and margin to date with their %; measured, invoiced, received) around a `.progress-cell` for the budget used (`accent`, `warn` past 90%, `bad` past 100%, the fill capped at 100%), then `recordTable`s (bills by payee; each bill, a row opening the payment) and a `.panel` of hours by employee whose `.panel__body` hint names the sites and the rate. Empty states point to the action (New bill, Choose sites).
- **Client updates tab**: a `.worklist--wrap` of posted updates (period as title, the first paragraph as detail, `relDay` as when). The form drafts into a `textarea.txt` (10 rows) from **Draft with AI** (a `btn--sm` that reads "Writing…" while it waits) and says what the draft came from in a `.hint`; the detail renders the text as `.panel__text` paragraphs with Copy (`btn--primary`) and the WhatsApp link.
- **Retention** (Measurements tab): a `.panel` with held and released as `.dash-facts--row`, the invoice button in `.panel__tools` (`btn--accent`, only once the project is finished), a `.hint` when it isn't yet, and a table of the releases' invoices.
- **PDF buttons** for Building's documents are `pdfUrlButton(url, label, downloadName)` (billing.js), the CRM's `.pdf` button for any URL; the diary's period form is a drawer with two dates.
- **My sites** (an employee's page): a `.worklist` of their sites (`ok` when today's diary is written, `warn` when not, with the same Written / To write pills as the Site diary board); a site opens a `.record` drawer with the record card, a Today on site panel, the phases as `.progress-cell` rows, the issues as a `.worklist` and the photos grid.

### The client's page (`/app/share/{token}`)

`body.share-page` with one `main.share` column (760px, no sidebar or topbar): `.share__head` (the company's logo on `--doc-page` in `.share__logo`, the company's name, the project's number as `.share__eyebrow`, `.share__title`, `.share__sub` with status and address), a `.panel` with `.share__figure` (the percentage at 40px) over the 12px `.progress` and `.dash-facts` (planned start, end, started), **News from the team** when the team posted updates (`.share__update` with the period as heading and the text as `.panel__text` paragraphs), a **Plan** panel of `.share__phase` rows (name and dates, bar, state pill: never "late", which is the team's word), **Updates** (`.share__update`: the day, weather, work done as `.panel__text`, its photos in a `.photo-grid`), all shared **Photos**, and `.share__foot` (updated when, "made with"). Photos open in `dialog.share-viewer` (previous, close, next; arrow keys and Escape) over the house scrim, darker. The page is in the company's language unless the visitor chose another on that browser, and in the visitor's theme; it sends `noindex`.

### The change approval page (`/app/share/change/{token}`)

The same shell (`body.share-page`, `main.share`, `.share__head` with the change's title, number, project and an addition/omission pill), a `.panel` with `.share__figure` (the amount, "added to" or "taken off the price") over `.dash-facts` (time, sent), the lines as a `.tbl.tbl--stack`, and a **Your decision** panel. While the change waits: a `form.form` with the name (required, `autocomplete="name"`), an optional note, the consent `.hint`, a `.notice.notice--warn` for errors (`role="alert"`, hidden until needed) and `.share__decide` with **Approve** (`btn--primary`) and **Reject** (`btn--ghost`), both `type="button"` so Enter in a field never decides. Once decided: `.share__decided`, the pill (`pill--ok` approved, `pill--bad` rejected), `.share__decided-by` (who and when) and the note; a `.notice.notice--info` thanks the client right after they answer.

## Utilities

`.row` `.col` `.muted` `.mono` `.sub` `.right` — use these instead of one-off flex/color classes.

`.visually-hidden` keeps text for screen readers only: live-region announcements (the inbox's "New message from …") and labels of controls that show just an icon.

## Sidebar KPIs

```html
<div class="sidebar__foot">
  <div class="kpi">
    <div class="kpi__label">Receivable</div>
    <div class="kpi__value">€ 0,00</div>
  </div>
  <div class="meta"><span>v 1.0</span><span class="meta__sep">·</span><span id="meta-clock">—</span></div>
  <div class="sidebar__credit"><span data-i18n="app.poweredBy">Powered by</span> <strong>The Bots Lab</strong></div>
</div>
```

`.sidebar__credit` is `/app`'s technology-provider line: a small bolt (CSS mask) plus muted text, last in the foot. It must stay quieter than the tenant's brand at the top — no logo, no accent color, no link. "Powered by" goes through i18n; "The Bots Lab" is the product name and is not translated.

The backoffice's foot ends with a `.sidebar__session`, shown only under 620px, where its `.topbar--one-row` leaves the theme switch and Log out out of the bar:

```html
<div class="sidebar__session">
  <button class="iconbtn iconbtn--theme" type="button" data-theme-toggle></button>
  <button class="btn btn--ghost" id="nav-logout" type="button" data-nav-close data-i18n="backoffice.logout">Log out</button>
</div>
```

`theme.js` paints every `[data-theme-toggle]` like `#btn-theme` (icon, `title` and `aria-label`) and toggles the theme on its click, keeping the menu open; a click on a `[data-nav-close]` closes the menu, like a nav item.
