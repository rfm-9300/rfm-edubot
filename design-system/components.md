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
```

- `.btn` and `.btn--primary` / `.btn--accent` are the same CTA (gradient + `--accent-ink` + `--glow-accent`). Prefer `--primary` for page CTAs, `--accent` for in-row / drawer save.
- `.btn--ghost` for cancel, logout, secondary.
- `.btn--danger` only for irreversible confirm. Pair with `.confirm`.
- `.btn--sm` in table action cells and compact toolbars.
- `.iconbtn` is 30×30, radius 10px. Theme toggle uses `.iconbtn--theme` (36×36 pill).
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

## Nav

```html
<nav class="nav">
  <a class="nav__item is-active" data-tab="overview" href="#overview">
    <span class="nav__dot"></span>
    <span class="nav__label">Overview</span>
    <span class="nav__count">3</span>
  </a>
</nav>
```

Active = `.is-active`. New tabs **must** get a `data-tab` (or `href`) rule in `style.css` for `--tab` color and `.nav__dot::after` emoji. Copy an existing tab block. Dark theme remaps overview/tenants to yellow.

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

Stats cycle personality colors by `nth-child` (accent, mint, coral, sky, sun, grape). Numeric values use `.stat__value`; tinted emphasis uses `.stat__value--accent`.

## Panel + chips

```html
<div class="panel">
  <div class="panel__head">
    <h2 class="panel__title">Directory <span class="tag">12</span></h2>
    <div class="panel__tools">
      <button class="chip is-on" type="button">All</button>
      <button class="chip" type="button">Open</button>
    </div>
  </div>
  <!-- table or body -->
</div>
```

`.chip.is-on` = selected filter (solid accent). `.tag` inside `.panel__title` is a count pill.

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

Row markers (left inset bar): `.is-overdue` (bad), `.is-paid` (ok), `.is-draft` (faint). Clickable rows may use `.conversation-row`.

## Empty

```html
<div class="empty">
  <p class="empty__title">Nothing here</p>
  <p class="empty__desc">Create the first item to get started.</p>
</div>
```

Place inside a table cell with `colspan`, or in a panel body. The wand glyph is CSS (`::before`). Do not add a second illustration.

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

Show/hide with the `hidden` attribute, not a CSS class.

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

User bubbles use the gradient; bot bubbles use surface + hairline. Do not invert that.

## Bookings calendar

Reuse `.booking-toolbar`, `.cal-grid`, `.cal-event`, `.cal-event--confirmed|pending|cancelled|completed`. Do not introduce a third-party calendar skin.

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
- Preview chrome inside the page (`--doc-brand`, `.tpl-kicker`, `.tpl-table`, `.tpl-total`) is document ink, not dashboard `--ink`.
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

## Utilities

`.row` `.col` `.muted` `.mono` `.sub` `.right` — use these instead of one-off flex/color classes.

## Sidebar KPIs

```html
<div class="sidebar__foot">
  <div class="kpi">
    <div class="kpi__label">Receivable</div>
    <div class="kpi__value">€ 0,00</div>
  </div>
  <div class="meta"><span>v 1.0</span><span class="meta__sep">·</span><span id="meta-clock">—</span></div>
</div>
```
