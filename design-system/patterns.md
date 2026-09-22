# Patterns

Page-level recipes. Markup classes are defined in [components.md](components.md).

## Dashboard shell

Every `/app` and `/backoffice` page uses this structure:

```
body
  aside.sidebar
    .brand
    nav.nav
    .sidebar__foot
  main.main
    header.topbar
    section.view#view
  .drawer#drawer[hidden]
  .toast#toast[hidden]
  (.confirm#confirm[hidden] — when the surface deletes/overwrites)
```

`body` is a two-column grid (`--sidebar` | 1fr). Do not wrap this in a React-style app frame. Do not add a second top nav.

Head assets (order matters):

1. Google Fonts (Outfit, Nunito, JetBrains Mono) + preconnect
2. `/admin/style.css`
3. `/admin/theme.js` (synchronous, in `<head>`)
4. `/admin/catalog.en.js`, `catalog.pt.js`, `catalog.es.js`
5. `/admin/i18n.js`
6. Page script `defer` at end of `<body>`

## List module (default screen)

Used by clients, quotes, invoices, catalog, tenants:

1. `.view__hero` with title, description, optional `.view__stats`
2. `.panel` with `.panel__head` (title + optional `.chip` filters)
3. `.tbl-wrap` > `table.tbl`
4. Empty: one row, `colspan`, inner `.empty`
5. Row actions in `.actions` (`.btn--sm`, `.iconbtn`)
6. Create/edit in the **drawer**, not a full-page form
7. `#btn-new` in the topbar shows only when the active module can create

## Drawer form

1. `openDrawer({ eyebrow, title, body, wide, onSave, saveLabel })` (or equivalent)
2. Body is a `.form` / `.form__grid`
3. Footer: ghost Cancel + accent Save
4. On save: disable button, swap label to saving, close only on success
5. Focus first input after open
6. Close via `[data-close]`, scrim, and Escape; trap Tab inside the panel; restore focus on close
7. Wide (`.drawer__panel--wide`) for line-item editors
8. Quote / invoice / client rows open the drawer for status, convert, or edit — not a new page

## Login

Render `.auth` > `.auth__card` into `#view`. Keep brand mark letters consistent with the surface (CRM / AI / BO). POST existing auth endpoints; do not add a new login visual.

## Confirm then destroy

Never `window.confirm`. Fill `.confirm__title` / `.confirm__body`, show `#confirm`, ghost cancel + `.btn--danger` confirm.

## Toast feedback

Success and recoverable errors: `toast(translatedString)`. Do not use `alert()`. One node, ~2.8s.

## Filters

Chip group in `.panel__tools`. Selected chip gets `.is-on`. Filtering is client-side unless the module already hits an API query param. When the filter is a long entity list (clients on Serviços), use a compact `.sel` beside the chips instead of one chip per row.

## Home (tenant snapshot)

`/app` overview is a manager snapshot of **enabled modules**, not a KPI wall of raw counts. It should read as graphical — big tinted numbers and module color, not a stack of label/value form rows:

1. `.view__hero` with 3–4 processed highlights as `.stat--lg` spotlight tiles (collected this month, outstanding, open quotes, waiting chats — only for modules the tenant has on). A highlight's hint gets `.delta--up` / `.delta--down` from its `deltaPct` sign, not plain muted text.
2. `.pulse` health strip (`pulse--ok` / `--watch` / `--urgent`) with a `.pulse__icon` and a one-line summary
3. `.queue` of items that need a person (overdue invoices, waiting chats, pending bookings, unreplied Instagram, expiring quotes), each row led by a `.queue__icon`. The "Needs you" heading carries a `.tag` count.
4. `.home-grid` of `.snapshot` panels — one per enabled operational module (money, pipeline, clients, services, inbox, calendar, Instagram, catalog, assistant). Each card gets a module `data-kind` (drives its `--snap` accent + `.snapshot__icon`), a single large `.snapshot__figure` headline number, an optional `.meter` when the number is a ratio, and secondary numbers in a `.snapshot__metrics` grid — not a vertical label/value list. Tenants pick which of these appear under Settings → Home (`GET`/`PUT /app/api/settings/overview`). Hidden cards stay off until turned back on; new modules still show by default.
5. `.setup-list` only when setup is actually unfinished **and** relevant (CRM-only tenants are not asked to connect WhatsApp)

Home has a **Choose cards** control in `.home-title-row` next to the page title (not in the highlight stats). It opens Settings → Home. Snapshot clicks set `data-go` (and optional `data-conversation` / `data-settings`) then switch module. Data comes from `GET /app/api/overview`; do not fan out to every module list to render Home.

## Services (client work)

`/app` Services is a CRM table of work attached to a **client**, not the booking-type catalog. Recipe: `.view__hero` + stats (open / invoiced) + `.crm` panel with a compact `.sel` client filter, status chips, and an Invoice selected action. Stats and rows follow the chosen client. Rows use a leading checkbox (`.tbl td.check`) so several open rows for the same client can become one invoice. New/edit opens the drawer (New service prefills the filtered client). Prefill from catalog or booking service types is optional; the client is required.

## Conversation / assistant

Two-column `.assistant` on desktop; stacks at `760px`. Transcript uses `.chat__*`. Tool-call confirmation uses `.assistant__action` (accent border, confirm + cancel). Do not auto-execute. After a confirmed `create_invoice` / `create_quote`, reuse `.pdf` in `.assistant__action-buttons` so the user can download the generated document.

`/app` Conversations is this same split inbox (thread list + live reply), not a table that opens a drawer.

## Instagram (comments inbox)

Optional `instagram` module. Work queue first, not an Insights wall:

1. `.view__hero` + unreplied / posts stats
2. Chip filter: needs a reply vs posts (`.panel__tools` chips)
3. Comment/post tables with `.ig-thumb` in the first column; row click opens the **drawer**
4. Drawer lists `.ig-comment` items and a reply form; do not auto-send
5. Empty / not-connected / reconnect copy goes through i18n. Reconnect is Settings → Channels.

## Settings (tenant)

Chip tabs (`.settings-tabs`): Home · Channels · Website · Language · Documents. Home is a `.choice-list` of `.queue__item.choice` toggles (visible cards get `.is-on`). Website includes snippet, allowed origins, and a `.widget-preview`. Documents mounts the template studio. Do not dump every settings panel into one scroll.

## Document template studio

Settings → Quote & invoice template is a three-pane studio (layers · A4 stage · inspector), not a stacked form. Script: `app/doc-template.js`. Persist via `PUT /app/api/settings/document-template` (`layout`, `accentColor`, `showDecor` plus the existing copy fields). Empty `layout` keeps the historical PDF geometry.

## New dashboard page

1. Add a `nav__item` with `data-tab` / `href`.
2. Add `--tab` color + emoji in `style.css` next to the other tab personality rules.
3. Render into `#view` with hero + panel (or a documented special layout: assistant, calendar, settings).
4. Add i18n keys in all three catalogs.
5. Do not create `page.css` or a new layout grid.

## Responsive

At `max-width: 920px`:

- Sidebar becomes a slide-over; `#btn-nav` + `#nav-scrim` toggle it
- Topbar search stays visible on a second row (`grid-column: 1 / -1`); hide only `kbd`
- View padding shrinks; hero stacks
- Drawer goes full width, square corners

Do not hide primary CTAs or the search field at this breakpoint.

## Widget (not the dashboard)

`widget.css` / `widget.js` is an embed on third-party sites:

- Prefix every class `tbl-`
- Host may set `--tbl-accent`
- Do not depend on dashboard tokens or fonts
- Keep the bubble + panel + message list; do not restyle it to Outfit/Nunito
