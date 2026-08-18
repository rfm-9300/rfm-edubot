# Tokens

All dashboard visuals come from CSS custom properties on `:root` (light) and `html[data-theme="dark"]` (dark). Defined in `src/main/resources/admin/style.css`.

Theme persistence: `admin/theme.js` writes `html[data-theme]` from `localStorage.uiTheme` (`"light"` | `"dark"`). Default is **light**. Load `theme.js` in `<head>` so the theme applies before first paint.

## Color — light (default)

| Token | Value | Role |
|---|---|---|
| `--bg` | `#f3f4fb` | Page canvas |
| `--bg-deep` | `#eceef7` | Recessed areas |
| `--bg-elev` | `#ffffff` | Elevated chips / counts |
| `--surface` | `#ffffff` | Panels, sidebar, inputs |
| `--surface-2` | `#f7f8fd` | Panel heads, table header, hover fill |
| `--line` | `#e7e9f4` | Default border |
| `--line-soft` | `#eef0f8` | Hairline / row divider |
| `--hairline-strong` | `#d9dcee` | Hover border, scrollbar |
| `--ink` | `#23263b` | Primary text |
| `--ink-2` | `#4b4f68` | Secondary text |
| `--ink-mute` | `#7e8299` | Labels, captions |
| `--ink-faint` | `#a8abc0` | Placeholders, meta |
| `--accent` | `#7c5cfc` | Violet accent |
| `--accent-deep` | `#6847e8` | Accent text on light tints |
| `--accent-soft` | `rgba(124, 92, 252, 0.12)` | Focus ring, soft fill |
| `--accent-ink` | `#ffffff` | Text on accent / gradient |
| `--ok` / `--ok-soft` / `--ok-ink` | `#14b88a` / tint / `#0b8a66` | Success |
| `--warn` / `--warn-soft` / `--warn-ink` | `#f5a524` / tint / `#ad6800` | Warning |
| `--bad` / `--bad-soft` / `--bad-ink` | `#f4537e` / tint / `#d62e60` | Danger |
| `--info` / `--info-soft` / `--info-ink` | `#4596ff` / tint / `#1d6fe0` | Info |
| `--mint` `#2dd4a8` · `--coral` `#ff7a8a` · `--sky` `#38bdf8` · `--sun` `#fbbf24` · `--grape` `#c06cf6` | Personality tints (nav tabs, stats) |
| `--mix` | `#ffffff` | Mix base for `color-mix(...)` tints |
| `--grad` | `135deg, accent → grape` | Primary CTA fill |
| `--grad-hover` | `135deg, accent-deep → grape` | Primary CTA hover |

## Color — dark (`html[data-theme="dark"]`)

| Token | Value | Role |
|---|---|---|
| `--bg` | `#0b0d0f` | Canvas (thebots.lab) |
| `--bg-deep` | `#08090b` | Sidebar, recessed chat log |
| `--bg-elev` | `#111418` | Elevated |
| `--surface` | `#0f1216` | Panels |
| `--surface-2` | `#14181d` | Panel heads |
| `--line` | `rgba(232,234,237,0.10)` | Border |
| `--line-soft` | `rgba(232,234,237,0.06)` | Divider / grid |
| `--hairline-strong` | `rgba(232,234,237,0.16)` | Strong border |
| `--ink` | `#e8eaed` | Primary text |
| `--ink-2` | `#b8bcc2` | Secondary |
| `--ink-mute` | `#7a8089` | Muted |
| `--ink-faint` | `#565c64` | Faint |
| `--accent` | `#ffd60a` | Yellow accent |
| `--accent-deep` | `#e6c009` | Deep yellow |
| `--accent-soft` | `rgba(255,214,10,0.14)` | Soft yellow |
| `--accent-ink` | `#0b0d0f` | Text on yellow |
| `--ok` / `--ok-ink` | `#4ade80` / `#6ee7a0` | Success (bright on dark) |
| `--warn` / `--warn-ink` | `#fbbf24` / `#fcd34d` | Warning |
| `--bad` / `--bad-ink` | `#ff6b6b` / `#ff9d9d` | Danger |
| `--info` / `--info-ink` | `#7aa2ff` / `#a9c1ff` | Info |
| `--mix` | `#14181d` | Mix base |
| `--grad` | `135deg, #ffd60a → #ffb020` | Primary CTA |
| `--grad-hover` | `135deg, #f2c50a → #f5a524` | Primary CTA hover |

Status `*-soft` values on dark are `rgba(color, 0.12)`.

On dark, text that sits on an accent tint should use `var(--accent)` (not `--accent-deep`). Existing overrides: `.panel__title .tag`, `.pill--accent`, `.drawer__eyebrow`, `.auth__eyebrow`, `.pdf:hover`, `.lines__total .v`.

## Type

| Token | Value | Use |
|---|---|---|
| `--sans` | `"Nunito", ui-sans-serif, system-ui, sans-serif` | Body, labels, buttons, table cells |
| `--display` | `"Outfit", var(--sans)` | Page titles, brand, auth title, empty title, drawer title, stat values |
| `--mono` | `"JetBrains Mono", ui-monospace, "SF Mono", Menlo, monospace` | IDs, money, KPIs, timestamps, kbd, settings keys |

Google Fonts (all three dashboard `<head>`s):

```
Outfit:500;600;700;800
Nunito:400;600;700;800
JetBrains Mono:400;500;600
```

Body: `14px / 1.5`, antialiased. Do not add a fourth family.

| Role | Size | Weight | Font |
|---|---|---|---|
| Page title `.view__title` | 28px | 700 | display |
| Auth title | 28px | 700 | display |
| Drawer title | 20px | 700 | display |
| Stat value | 22px | 700 | display |
| Brand name | 14.5px | 700 | display |
| Nav item | 13.5px | 700 | sans |
| Button | 13px | 800 | sans |
| Table body | 13px | 600 | sans |
| Uppercase label (`.lbl`, `.panel__title`, th) | 10.5–12px | 800 | sans, `letter-spacing: 0.05–0.08em`, uppercase |
| KPI / ID / money | 12–13px | 500–600 | mono, `font-variant-numeric: tabular-nums` |

## Radius

| Token | Value | Typical use |
|---|---|---|
| `--r-xs` | `8px` | Tiny controls |
| `--r-sm` | `12px` | Inputs (`.inp`, `.sel`, `.txt`) |
| `--r-md` | `14px` | Line-item editors, compact cards |
| `--r-lg` | `20px` | Panels, stats, sidebar foot |
| (literal) | `999px` | Buttons, chips, pills, search, toast |
| (literal) | `24px 0 0 24px` | Drawer panel |
| (literal) | `28px` | Auth card |
| Brand mark | `13px` | 40×40 square |

## Elevation

| Token | Light | Dark |
|---|---|---|
| `--shadow-sm` | soft ink 5% | near-black 35–45% |
| `--shadow-md` | medium | used on auth card |
| `--shadow-lg` | large (drawer, confirm, toast) | 60% black |
| `--glow-accent` | violet 35% | yellow 22% |
| `--doc-page` | `#ffffff` | `#ffffff` (paper stays white) |
| `--doc-page-ink` / `--doc-page-muted` / `--doc-page-faint` | PDF preview text | same |
| `--doc-page-surface` / `--doc-page-wave` / `--doc-page-rule` | table fill, waves, snap grid | same |
| `--doc-page-shadow` | soft ink | heavier black |
| `--doc-brand` / `--doc-brand-ink` | template accent + contrast text; set on `.tpl__page` from JS | same |

Light: shadows. Dark: hairline borders + restrained shadows. Do not add heavy drop shadows on dark.

## Motion

| Duration | Easing | Use |
|---|---|---|
| 100–120ms | default | hover color / border / translateY(-1px) |
| 160ms | ease | scrim fade |
| 180ms | `cubic-bezier(0.22, 1, 0.36, 1)` | confirm pop-in |
| 220ms | same | toast |
| 240ms | same | drawer panel |

Hover lift is `translateY(-1px)` (buttons, chips, icon buttons) or `translateX(2px)` (nav items). Do not add bounce or long fades.

## Focus

```css
border-color: var(--accent);
box-shadow: 0 0 0 4px var(--accent-soft);
```

Used by `.inp:focus`, `.sel:focus`, `.txt:focus`, `.topbar__search:focus-within`. Reuse this ring. Do not use browser outline as the only focus style on interactive controls.

## Layout constants

| Token | Value |
|---|---|
| `--sidebar` | `256px` |
| Body grid | `grid-template-columns: var(--sidebar) 1fr` |
| Main breakpoint | `max-width: 920px` → single column, horizontal nav |
| Secondary | `860px` settings rows, `760px` assistant, `620px` chat/asset picker |

## Background texture

- Light: three pastel radial blobs on `body::before` (violet, mint, coral).
- Dark: 32px terminal grid from `--line-soft`, masked so it fades at top/bottom.

Do not replace these with a solid fill unless removing texture on a specific inner surface (chat log already uses `--surface-2` / `--bg-deep`).
