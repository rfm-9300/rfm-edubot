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

## Layout skins (`html[data-layout]`)

A second axis next to the theme. `classic` is everything above: the base layer. `minimal` is the skin of both product surfaces, `/app` and `/backoffice` (the "Clean Ops" look first made for tenants such as Family Clean): light-gray canvas, white cards, near-black text, yellow only for actions, selections and small highlights; no gradients, glow, textures or emoji.

- The skin is fixed in markup: `/app/index.html` and `/backoffice/index.html` declare `<html data-layout="minimal">` (the backoffice since 2026-10-04). `/admin` only redirects, so no page shows the classic layer on its own; change it only as the base the skin builds on. There is no layout switch (`theme.js` only handles the theme and fires `ui:theme`); an old `localStorage.uiLayout` value is ignored.
- Minimal overrides tokens on `html[data-layout="minimal"]` (light) and `html[data-layout="minimal"][data-theme="dark"]`. The dark block must redeclare **every** color the light block sets — both selectors otherwise tie with `html[data-theme="dark"]` and the later one wins.

| Token | Minimal light | Minimal dark |
|---|---|---|
| `--bg` / `--surface` / `--surface-2` | `#f7f8fa` / `#ffffff` / `#f9fafb` | `#0c0e12` / `#111318` / `#161a20` |
| `--line` / `--line-soft` / `--hairline-strong` | `#e4e7ec` / `#eef0f3` / `#d0d5dd` | white 8% / 5% / 14% |
| `--ink` / `--ink-2` / `--ink-mute` / `--ink-faint` | `#111318` / `#344054` / `#667085` / `#98a2b3` | `#f0f1f3` / `#c1c5cd` / `#858b96` / `#5d636e` |
| `--accent` / `--accent-hover` | `#f5d90a` / `#e6cb00` (yellow, yellow-hover) | same |
| `--accent-soft` | `#fff9d6` (yellow-soft: active nav, icon wells, selected chips) | yellow 12% |
| `--accent-deep` | `#735f00` — accent *as text* on light surfaces | `#e6cb00` |
| `--accent-ink` | `#111318` (text on yellow) | `#0b0d0f` |
| `--focus-border` / `--focus-ring` | `--ink` / 1px ink ring + 4px yellow halo | `--accent` / 1px yellow ring + faint halo |
| `--ok` `--warn` `--bad` `--info` | `#12b76a` `#f79009` `#f04438` `#2e90fa` (inks darker) | `#32d583` `#fdb022` `#f97066` `#53b1fd` (inks lighter) |
| `--grad` / `--grad-hover` | solid `--accent` / `--accent-hover` | same |
| `--glow-accent` | transparent (no glow) | same |
| `--r-xs` … `--r-lg` | `6` / `8` / `10` / `12px` | inherited from light |
| `--sidebar` | `240px` | inherited |
| `--shadow-*` | soft ink (`--shadow-sm`: 1px + 3px at 4–6%) | black 40–60% |

Yellow is a fill. On white it is far below text contrast, so never color text or thin lines with `--accent` in the light skin: text on tints uses `--accent-deep` (or `--ink`, as the active nav item and selected chips do), and trend lines use `--accent-deep`. `--ink-faint` is decorative in minimal (bars); text uses `--ink-mute` or darker (≥ 4.5:1 on `--bg` and `--surface`). Components that read tokens restyle for free; add a `html[data-layout="minimal"]` rule only for what tokens can't express (uppercase labels, emoji, literal `999px` radii, heavy font weights).

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

The minimal skin retunes a few roles: page title 30px / 700 (26px under 920px), stat value 20px / 700, panel title 15px / 600 display, nav item 13.5px / 600 (active 700), buttons 600, and sentence-case labels instead of uppercase. Sidebar money values use sans 700.

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
| `--widget-preview-light-*` / `--widget-preview-dark-*` | Fixed light and dark surfaces for the widget customizer preview | same |
| `--widget-preview-on-accent` | Fallback preview text color; JS computes the accessible dynamic value | same |

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
border-color: var(--focus-border);
box-shadow: var(--focus-ring);
```

Used by `.inp:focus`, `.sel:focus`, `.txt:focus`, `.topbar__search:focus-within`, and `:focus-visible` on `.btn`, `.chip`, `.iconbtn`, `.nav__item`. Reuse this ring. Do not use browser outline as the only focus style on interactive controls.

Classic resolves the tokens to `--accent` / `0 0 0 4px var(--accent-soft)`. The `/app` light skin uses an ink edge plus a yellow halo, because a yellow ring alone is invisible on white. Inset rings on borderless rows (`.dash-kpi`, `.worklist__item`, `.rank__item`) use `inset 0 0 0 2px var(--focus-border)`.

## Layout constants

| Token | Value |
|---|---|
| `--sidebar` | `256px` |
| Body grid | `grid-template-columns: var(--sidebar) 1fr` |
| Main breakpoint | `max-width: 920px` → single column, horizontal nav |
| Secondary | `860px` settings rows, `760px` assistant, `620px` chat/asset picker |
| Phone tables | `700px` `.tbl--stack` rows become cards (four columns of values, two under `560px`) |

## Background texture

- Light: three pastel radial blobs on `body::before` (violet, mint, coral).
- Dark: 32px terminal grid from `--line-soft`, masked so it fades at top/bottom.

Do not replace these with a solid fill unless removing texture on a specific inner surface (chat log already uses `--surface-2` / `--bg-deep`). The minimal skin (`/app`, `/backoffice`) is the exception: it hides `body::before` and keeps a solid `--bg`.
