# thebots.lab UI Design System

Source of truth for every **dashboard web UI** in this repo. Agents and LLMs must follow this folder whenever they add, change, or restyle HTML, CSS, or JS in the frontends.

**Implemented CSS lives in** [`src/main/resources/admin/style.css`](../src/main/resources/admin/style.css). This folder documents it. Do not invent a parallel visual language.

## Read this first

| File | When to open it |
|---|---|
| [AGENTS.md](AGENTS.md) | **Always** — hard rules, do/don't, checklist |
| [tokens.md](tokens.md) | Colors, type, radius, elevation, motion |
| [components.md](components.md) | Class catalog + copy-paste HTML |
| [patterns.md](patterns.md) | Page recipes (shell, list, login, drawer, empty) |
| [i18n.md](i18n.md) | Translatable strings (en / pt-PT / es) |

## Surfaces

| Surface | URL | Files | Stylesheet |
|---|---|---|---|
| Tenant Dashboard | `/app` | `src/main/resources/app/` | **shared** `/admin/style.css` |
| Backoffice | `/backoffice` | `src/main/resources/backoffice/` | **shared** `/admin/style.css` |
| Shared assets | `/admin/{asset}` | `src/main/resources/admin/style.css`, catalogs, `theme.js` | — |
| Retired CRM | `/admin` | redirects to `/backoffice/` | — |
| Website widget | embed | `src/main/resources/widget/` | **separate** `widget.css` (`tbl-` prefix) |
| Legal pages | `/legal/…` | `src/main/resources/legal/` | **standalone** inline CSS |
| Mobile | n/a | `mobile/` | **out of scope** (KMP) |

`/app` and `/backoffice` are the two product surfaces. They share one design system. `/admin` now only hosts those shared assets (and redirects the old CRM page to backoffice). Widget and legal are not in this system.

The same tokens and class vocabulary are used by **Extractor** (`/Users/rodrigomartins/projects/Extractor/design-system/`) in a compact topbar shell.

## How the two dashboards stay in sync

1. One stylesheet: `src/main/resources/admin/style.css`, served at `/admin/style.css`.
2. One theme switcher: `src/main/resources/admin/theme.js` (`html[data-theme]`, `localStorage.uiTheme`). If the user has never chosen, follow `prefers-color-scheme`. Labels come from `common.themeToLight` / `common.themeToDark`.
3. One i18n runtime: `admin/i18n.js` + `admin/catalog.{en,pt,es}.js`.
4. Shared class vocabulary: `.sidebar`, `.nav`, `.topbar`, `.btn`, `.panel`, `.tbl`, `.pill`, `.drawer`, `.form`, `.inp`, `.toast`, `.auth`, …

New dashboard CSS goes into that one stylesheet. New copy goes into all three catalogs.

## Visual identity (two themes)

- **Light (default)** — soft paper, rounded, pastel blobs, violet accent `#7c5cfc`. Fonts: Outfit (display) + Nunito (UI) + JetBrains Mono.
- **Dark** — thebots.lab terminal: `#0b0d0f` canvas, yellow accent `#ffd60a`, 32px hairline grid. Same type stack.

Theme is a token swap. Components consume CSS variables. Do not hardcode hex in new rules unless adding a new token.

## Agent workflow

1. Read [AGENTS.md](AGENTS.md).
2. Reuse an existing component from [components.md](components.md). Add CSS only if nothing fits.
3. Put user-facing copy through i18n ([i18n.md](i18n.md)).
4. Verify light **and** dark, plus the `920px` breakpoint.
5. If you add a token or component, update this folder in the same change.
