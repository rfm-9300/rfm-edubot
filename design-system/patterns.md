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

Chip group in `.panel__tools`. Selected chip gets `.is-on`. Filtering is client-side unless the module already hits an API query param.

## Home (work queue)

`/app` overview is a work queue, not a KPI wall:

1. `.view__hero` + today’s message/contact stats
2. `.queue` of items that need a reply (waiting chats, overdue invoices, pending bookings, unreplied Instagram comments)
3. `.setup-list` of unfinished setup (channels, website widget, persona)
4. A second stat row for conversations / quotes / invoices

Queue clicks set `data-go` (and optional `data-conversation` / `data-settings`) then switch module.

## Conversation / assistant

Two-column `.assistant` on desktop; stacks at `760px`. Transcript uses `.chat__*`. Tool-call confirmation uses `.assistant__action` (accent border, confirm + cancel). Do not auto-execute.

`/app` Conversations is this same split inbox (thread list + live reply), not a table that opens a drawer.

## Instagram (comments inbox)

Optional `instagram` module. Work queue first, not an Insights wall:

1. `.view__hero` + unreplied / posts stats
2. Chip filter: needs a reply vs posts (`.panel__tools` chips)
3. Comment/post tables with `.ig-thumb` in the first column; row click opens the **drawer**
4. Drawer lists `.ig-comment` items and a reply form; do not auto-send
5. Empty / not-connected / reconnect copy goes through i18n. Reconnect is Settings → Channels.

## Settings (tenant)

Chip tabs (`.settings-tabs`): Channels · Website · Language · Documents. Website includes snippet, allowed origins, and a `.widget-preview`. Documents mounts the template studio. Do not dump every settings panel into one scroll.

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
