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
- In the `/app` skin (`html[data-layout="minimal"]`) buttons are 36px with a 9px radius and semibold text: `--primary` / `--accent` is solid yellow with near-black `--accent-ink` text and darkens to `--accent-hover`; `--ghost` is white with a hairline border; `.iconbtn--theme` is a 36×36 rounded square.
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

Mark is 40×40, gradient, display font. Surfaces: CRM SVG house, app `"AI"`, backoffice `"BO"`. Keep that 2-letter / SVG convention.

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

Active = `.is-active`. New tabs **must** get a `data-tab` (or `href`) rule in `style.css` for `--tab` color and `.nav__dot::after` emoji (classic: backoffice), and a `--nav-icon` outline mask in the minimal block (`/app`). Copy an existing tab block. Dark theme remaps overview/tenants to yellow.

In the `/app` skin the active item is a `--accent-soft` fill with a 3px `--accent` bar on the left (`box-shadow: inset 3px 0 0`), `--ink` text at weight 700 and a dark icon; icons follow the label color. Group labels are small sentence-case `--ink-mute` text. Items are 32px tall; the sidebar scrolls when the nav outgrows the window.

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

Order: crumb · search · actions. Theme button is always in actions. Search hides below 920px.

`/app` has no layout switch: its skin is fixed in markup (`<html data-layout="minimal">`). There, `.topbar__search` draws its magnifier with `::before` (no extra markup), sits on `--bg` with a hairline border and a 10px radius, and focuses with the shared ring. The `/` hint is `aria-hidden`; the input gets its accessible name from `data-i18n-aria-label="app.searchPlaceholder"`.

On `/app` the actions are: theme · notifications bell (`#btn-notifications`) · account (`#btn-account`, `.iconbtn--avatar`) · Log out · New. The bell shows once someone is signed in, operators included; the account button stays `hidden` until the signed-in user is known, and stays hidden when an operator opens the dashboard (no user account). `#btn-new` is `span.btn__plus` + `span.btn__label` ("+ New client"); below 620px it keeps only the plus, a 36px square, with the label kept for screen readers.

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

In the `/app` skin `.view__stats` is one white card (hairline border, `--r-lg`, `--shadow-sm`) whose cells are split by inset hairlines; it is a grid of equal columns and drops to two columns under 700px.

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

Cell helpers: `.name` `.id` `.muted` `.num` `.mono` `.right` `.actions`.

In the `/app` skin the list page's directory card — the `.panel` straight under `.view` — uses 20px side padding for its head, filters and cells (16px under 920px), a 58px head and a lightly tinted `--surface-2` header row. Panels inside drawers and Home cards keep the compact 16px spacing.

Row markers (left inset bar): `.is-overdue` (bad), `.is-paid` (ok), `.is-draft` (faint), `.is-current` (accent, the period that contains today). `.is-total` is the compiled totals row (surface background, heavier weight). `.is-day` is a full-width group header row (one `td colspan`) for date-grouped lists such as the bookings agenda.

## Empty

```html
<div class="empty">
  <p class="empty__title">Nothing here</p>
  <p class="empty__desc">Create the first item to get started.</p>
</div>
```

Place inside a table cell with `colspan`, or in a panel body. The generic glyph is CSS (`::before`: a wand in classic, a line tray in the `/app` skin).

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

Keep it minimal and professional: outline strokes, at most a few accent shapes, no emoji or photos. Colors come only from `.art-line` / `.art-accent`, so dark mode works without extra rules. Only Clientes has one today (`CLIENTS_EMPTY_ART` in `app.js`: a client, a spray bottle, three sparkles).

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

- Grid: `.form__grid` (2 col), `.form__grid--3` (3 col), `.form__row--full` spans.
- Fields: `.inp` `.sel` `.txt`. Money/IDs: `.inp--mono` `.inp--right`.
- Labels: `.lbl` uppercase. Required: `.req`. Optional: `.opt`.
- Hints: `.hint`; `.hint--warn` for a warning, `.hint--bad` for an error (add `role="alert"` when it appears after an action).
- A single yes/no option inside a form: `<label class="form__check"><input type="checkbox" /> Label</label>` (accent-colored box, same size as `.tbl td.check`). Don't build a toggle switch.

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

## Line items

Use `.lines` / `.lines__head` / `.line` / `.lines__foot` for quote/invoice editors. Numeric inputs get `.num`. Remove button: `.l-rm`. Do not replace this with a generic table.

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

Show/hide with the `hidden` attribute, not a CSS class. On open: focus the first control, trap Tab inside the panel, Escape closes, and restore focus to the opener.

`openDrawer(title, body, wide, { eyebrow })` sets the eyebrow text (default "Dashboard"). A drawer opened from another drawer (a record, an invoice…) through `openFrom(back, open)` stacks its opener on a trail and replaces the eyebrow with `.drawer__back` (the opener's name; the ← glyph is CSS). Back pops one step, and a drawer that closes itself after an action (save, mark paid, convert) reopens its opener with fresh data. Closing by hand (×, scrim, Escape) clears the trail and leaves.

```html
<div class="drawer__eyebrow"><button class="drawer__back" type="button" aria-label="Back to Ana Ribeiro">Ana Ribeiro</button></div>
```

In a detail drawer (quote, invoice, payment, booking), the name in `.detail__head` becomes a `button.detail__client.detail__client--link` (accent, trailing →) that opens that client's or payee's record, unless the trail already leads back there.

## Record

Clients, suppliers and employees open as a record, not a form ([patterns.md](patterns.md#clients-directory--record)):

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
- The create actions are a sticky `.drawer__foot` at the end of the record.
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

One toast node per page. JS pattern: set innerHTML, `hidden = false`, auto-hide ~2800ms. Do not stack toasts.

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

When Google sign-in is configured (`/admin/auth/config`, `/app/auth/config`), a `.btn--primary` "Continue with Google" button comes first, then an `.auth__desc` line ("or sign in with your email and password"), then the form with a `.btn--ghost` submit. A refusal that needs explaining (no linked user, a different Google account) shows under the Google button as `.hint.hint--warn` with `role="alert"`; cancelled or failed popups use the toast.

## Chat (persona / thread / assistant)

| Class | Role |
|---|---|
| `.chat__log` | Scrollable transcript |
| `.chat__msg chat__msg--user` | Outgoing (gradient) |
| `.chat__msg chat__msg--bot` | Incoming (surface + border) |
| `.chat__typing` | Italic muted |
| `.chat__form` + `.chat__input` | Composer (pill input) |
| `.assistant` | Two-column assistant shell |
| `.assistant__thread` / `.is-active` | Thread list item |
| `.assistant__action` | Confirm-before-execute card |

User bubbles use the gradient; bot bubbles use surface + hairline. Do not invert that. The customer inbox has its own components (next section).

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
| `.hbars` > `.hbars__row` (`__label` `__track` `__fill` `__value`) | Pipeline stages |
| `.rank` > `button.rank__item` (`__pos` `__name` `__value` `__bar` `__fill` `__fill--billed` `__meta`) | Top clients, billed vs paid |
| `.dash-split` · `.dash-figures` · `.dash-figure` (`--lg` `--end`, `__value--bad\|warn`, `__label`) | Headline numbers inside a card |
| `dl.dash-facts` (`--row`) · `.dash-sub` · `.dash-empty` (`--center`) · `.dash-axis` | Key/value rows, sub-heading, quiet empty line, sparkline axis |
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

Reuse `.booking-toolbar`, `.cal-grid`, `.cal-event`. Do not introduce a third-party calendar skin.

```html
<div class="booking-toolbar">
  <div class="booking-toolbar__views"><!-- Week / Agenda: .btn.btn--sm, active = .btn--primary --></div>
  <div class="booking-toolbar__nav period-nav"><!-- ‹ label › and "This week" once navigated away --></div>
  <div class="booking-toolbar__actions"><!-- Services · Opening hours --></div>
</div>
<div class="panel cal-wrap"><div class="cal-grid">
  <div class="cal-grid__dayhead is-today">…</div>
  <div class="cal-grid__cell is-today" data-booking-slot="2026-09-28T10:00">
    <button class="cal-event cal-event--confirmed" type="button">
      <span class="cal-event__time mono">10:00–11:00</span>
      <span class="cal-event__who">Ana Silva</span>
      <span class="cal-event__what">Consulta</span>
    </button>
  </div>
  <div class="cal-grid__cell is-closed"></div>
</div></div>
```

- Status modifiers: `.cal-event--pending|confirmed|completed|no-show|cancelled`, tinted from `--warn` / `--ok` / `--info` / `--bad` / `--ink-faint` (soft fill + 3px inset bar). No raw hex.
- `.is-today` marks today's column (accent underline on the head, faint tint on cells). `.is-closed` hatches hours outside the opening hours — staff can still click it to book.
- Grid cells, day keys and prefilled times are **tenant-local** (`Tenant.timezone`), never the browser zone.
- Opening-hours editor rows: `.booking-avail` > `.booking-avail-row` (day `.sel`, from/to `.inp`, remove `.iconbtn--danger`); `.booking-avail-row--head` carries the column labels.
- Free-time picker in the booking drawer: `.slot-picks` of `.chip` buttons (picked = `.is-on`).
- Agenda list: day group rows are `tr.is-day` (surface band, uppercase label in classic, sentence case in minimal).

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
    <div class="settings-row__actions"><button class="btn btn--sm btn--accent">Save</button></div>
  </div>
</div>
```

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
| `.notice` (`--warn` `--info`) > `__text` (`strong` + `span`, or `ul.notice__list`) + `__actions` | Inline banner in a view or record: agents paused, problems to fix before activating, the "let it act on its own" suggestion. Not a toast, not a modal |
| `.agent-icon` (`--lg`) | 32px (44px) line icon on the accent tint; the SVG comes from the agent's `icon` key |
| `.agent-cell` (`__text` `__name` `__sub`) | Agents table first column: icon, name, and a `.recipe` under it |
| `.recipe` > `.recipe__part` (`--when`) + `.recipe__arrow` | "When → step → step" chips; the trigger part is tinted; long parts truncate |
| `ol.flow` > `li.flow__step` (`--trigger`) + `li.flow__connector` | Read-only steps (agent overview, run, test run): `.flow__index` (number, or the bolt for the trigger) · `.flow__main` (`__eyebrow` `__title` `__detail` `__detail--bad`) · `.flow__side` (pills). `data-state="done\|failed\|waiting\|skipped"` tints a run step's index |
| `.flow--edit` > `.flow__card` (`__card-head` `__card-summary` `__card-tools`) | Builder cards, one per trigger or step: the type/action select in the head, the schema-driven `.form__grid` below, a `div.flow__connector` between steps |
| `.builder` > `section.panel.builder__section` · `.sel--inline` | Builder drawer sections (When, Only if, Steps, Stop rules, Rules, Voice); "Add …" selects are inline, not full width |
| `.var-tools` (`__select`) · `.var-chip` (`--static`) | Suggested `{{variables}}` as mono chips under a template field, plus a grouped select; `--static` shows a variable inside read-only text |
| `.conds` > `.cond-row` (`--exit`) | Condition rows: field · operator · value · remove `.iconbtn`; with two or more, a `.sel--inline` match select sits on top |
| `.chip-picks` | Multi-select chips (weekdays, statuses); picked = `.chip.is-on` + `aria-pressed` |
| `.inp-unit` (`__label`) · `.inp-range` | A number with its unit (localized with `Intl`), and a from–to pair of time inputs |
| `.gallery` > `button.gallery__card` (`--blank`, `.is-unavailable`) with `__head` `__title` `__desc` `__recipe` `__meta` | Template cards; unavailable ones stay clickable so the setup drawer can say what's missing |
| `.plain-list` · `.agents-settings` · `.empty__actions` | A bulleted rules list, the Settings tab's two-panel stack, a button row inside `.empty` |

Rules: status pills use tones, not new colors (agent: `--ok` active, `--warn` paused; run: `--info` in progress, `--warn` awaiting approval, `--ok` done, `--bad` failed or needs review). Trigger, action, event, field and reason labels come from `app.agents.*` keys built from server keys (dots become underscores), never from server text; what a finished run did reads from `app.agents.did.*` ("Sent a WhatsApp message"), falling back to the action label. An approval shows the drafted message in a `.wa-preview` bubble and its details in `dl.dash-facts`; nothing is sent from the list.

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
