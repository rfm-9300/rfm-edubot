# Web Redesign Plan — thebots.lab Dark-First Re-skin

> Regenerated after a session was lost mid-write. Reconstructed from the original
> brief + a fresh re-read of the live code so the token mapping is exact.

## Brief

> "Based on the system design of our company (the *Social Media Campaigns* and
> *The Bots Lab site* handoff zips), make a detailed plan to redesign our web pages."

## Decisions (confirmed in the original session)

1. **Visual direction → Dark-first, match brand.** Adopt the `thebots.lab` dark
   terminal aesthetic: `#0b0d0f` background, single sharp yellow accent `#ffd60a`,
   mono + sans pairing. (The brand also ships a full light theme — we keep it as a
   free `data-theme` override, see Phase 6.)
2. **Surfaces → all four:**
   - CRM Admin (`/admin`)
   - Tenant Dashboard (`/app`)
   - Backoffice (`/backoffice`)
   - A branded **login** experience

## The key architectural insight

All three HTML surfaces link **one stylesheet** — `src/main/resources/admin/style.css`
(496 lines), served at `/admin/style.css` — and share the same class vocabulary
(`.sidebar`, `.nav`, `.topbar`, `.btn`, `.panel`, `.tbl`, `.pill`, `.drawer`,
`.form`, `.inp`, `.toast`, …). All three already load **Hanken Grotesk + JetBrains
Mono** from Google Fonts.

> **Therefore this redesign is fundamentally a token + component re-skin of a single
> stylesheet,** plus a font-link swap in three `<head>`s and a login surface. It is
> *not* three separate redesigns.

The stylesheet today is driven entirely by CSS custom properties on `:root`
(`--bg`, `--ink`, `--accent`, `--surface`, status tokens, radii, shadows). Rewriting
that token block recolors every surface at once; component rules need only targeted
dark-mode corrections (shadows, hairlines, grid texture, status pills, scrollbar).

### Surface inventory (verified)

| Surface | HTML | JS | Login today |
|---|---|---|---|
| CRM Admin `/admin` | `admin/index.html` | `admin/app.js` (975 ln) | **None** — reads `adminToken` from `localStorage`, redirects if absent |
| Tenant Dashboard `/app` | `app/index.html` | `app/app.js` (103 ln) | Inline `renderLogin()` — email + password, reuses `.panel`/`.form` |
| Backoffice `/backoffice` | `backoffice/index.html` | `backoffice/app.js` (269 ln) | Inline `renderLogin()` — password only |

Auth backends already exist: `POST /admin/auth/login` (password → JWT) and
`POST /app/auth/login` (email+password → JWT). Static files are served generically
via classloader `getResource("admin/$asset")` in `AdminRoutes`/`BackofficeRoutes`/
`DashboardRoutes`, so **new asset files dropped into those resource dirs are served
automatically** — no route changes needed for new CSS/JS.

## Token mapping: current (light/terracotta) → target (dark/thebots.lab)

Current `:root` uses OKLCH for a "warm paper + terracotta" look. Target uses the
brand's hex tokens. Brand defines fewer tokens than the app needs, so we **keep the
app's richer token set** (surfaces, status colors, shadows) and re-anchor each to the
dark brand palette.

### Direct brand tokens (from `the-bots-lab-site/project/styles.css`)

```css
:root {
  --bg:             #0b0d0f;   /* was oklch(0.965 …) paper      */
  --bg-deep:        #08090b;   /* sidebar/strip, darker than bg */
  --bg-elev:        #111418;
  --surface:        #0f1216;   /* panels        (brand --panel)   */
  --surface-2:      #14181d;   /* panel heads   (brand --panel-2) */
  --line:           rgba(232,234,237,0.10);   /* brand --hairline, nudged up for tables */
  --line-soft:      rgba(232,234,237,0.06);

  --ink:            #e8eaed;
  --ink-2:          #b8bcc2;
  --ink-mute:       #7a8089;   /* brand --muted */
  --ink-faint:      #565c64;

  --accent:         #ffd60a;   /* was terracotta */
  --accent-deep:    #e6c009;
  --accent-soft:    rgba(255,214,10,0.14);   /* dark-tuned tint */
  --accent-ink:     #0b0d0f;   /* text ON accent */
  --danger / --bad: #ff6b6b;
}
```

### Status colors — re-tune for dark (app-specific, not in brand)

The app uses `--ok/--warn/--bad/--info` plus `*-soft` (background tint) and `*-ink`
(text) triplets for pills and row markers. On dark, the `-soft` tints must become
low-alpha overlays and `-ink` must become the *bright* readable variant:

```css
--ok:   #4ade80; --ok-soft:   rgba(74,222,128,0.12);  --ok-ink:   #6ee7a0;
--warn: #fbbf24; --warn-soft: rgba(251,191,36,0.12);  --warn-ink: #fcd34d;
--bad:  #ff6b6b; --bad-soft:  rgba(255,107,107,0.12); --bad-ink:  #ff9d9d;
--info: #7aa2ff; --info-soft: rgba(122,162,255,0.12); --info-ink: #a9c1ff;
```

### Shadows / texture (currently tuned for light)

- `--shadow-*` use dark-ink alpha that's invisible on a dark bg → switch to
  `rgba(0,0,0,0.45/0.6)` and rely more on `--hairline-strong` borders for elevation
  (brand convention: borders, not shadows).
- `body::before` grid texture uses dark lines on light → flip to
  `linear-gradient(var(--hairline) 1px, transparent 1px)` (brand's exact hero-grid
  recipe).
- `.sel` dropdown-arrow SVG stroke `%23555` → light (`%23b8bcc2`).
- `::-webkit-scrollbar-thumb` light grays → dark elevations.
- `html { color-scheme: light }` → `dark`.
- `.topbar` translucent bg `oklch(0.965 …/0.85)` → `rgba(11,13,15,0.72)` so the
  backdrop-blur reads on dark.

### Fonts

Brand sans is **Geist** (mono stays JetBrains Mono, already used). Swap the Google
Fonts link in all three heads from `Hanken Grotesk` → `Geist`, and update
`--sans`. Geist is on Google Fonts; keep the existing fallback stack.

## Implementation phases

### Phase 0 — Safety net
- Confirm clean tree; work on a branch (e.g. `feature/web-redesign`).
- Copy current `style.css` → `style.css.bak` locally (or rely on git) for quick diff.

### Phase 1 — Token rewrite (the 80%)
- Rewrite the `:root` block of `src/main/resources/admin/style.css` per the mapping
  above. Update the header comment to "thebots.lab · dark-first".
- This alone recolors all four surfaces. Review each before deeper edits.

### Phase 2 — Dark-mode component corrections
In the same stylesheet, fix the rules that assume light:
- `html` color-scheme, `body::before` grid, `.topbar` translucent bg.
- `--shadow-*` values; lean on `--hairline-strong` borders.
- Status pills (`.pill--*`) border colors (currently hardcoded `oklch(0.85 …)`
  light borders → derive from the new status tokens).
- `.sel` arrow SVG color; scrollbar; `.drawer__scrim`/`.confirm__scrim` overlay
  (already dark-ish, verify contrast).
- `.btn--primary` currently inverts to `--ink` bg / `--surface` text → on dark that's
  a light button; decide: keep as the high-contrast "light" CTA, or make accent the
  primary. Recommended: **accent = primary CTA**, keep `--primary` as neutral light.

### Phase 3 — Font swap
- In `admin/index.html`, `app/index.html`, `backoffice/index.html`: replace the
  `Hanken Grotesk` Google Fonts URL with `Geist`.
- Update `--sans` in `style.css`.

### Phase 4 — Login experience
- **Tenant + Backoffice:** re-skin the inline `renderLogin()` markup in
  `app/app.js` and `backoffice/app.js` into a centered, branded card (brand mark,
  eyebrow, accent CTA) instead of the current bare `.panel`. Pure markup/class
  changes — no new endpoints.
- **CRM Admin:** today it silently redirects when `adminToken` is missing. Add a
  matching `renderLogin()` to `admin/app.js` that posts to `POST /admin/auth/login`
  and stores `adminToken`, so the CRM is reachable without bouncing through the
  backoffice. (Verify the desired behavior with Rodrigo — see Open questions.)
- Optionally extract a shared `.auth` block in `style.css` (centered viewport card)
  used by all three.

### Phase 5 — Per-surface polish
- Brand marks: `admin` shows a logo SVG, `app` shows "AI", `backoffice` shows "BO".
  Align to brand mark style (square, hairline border, accent glyph) — already close.
- `--sidebar` width, `.brand__sub` mono eyebrows, `.drawer__eyebrow` accent — all
  already on-brand; just verify against dark tokens.

### Phase 6 — (Optional) Light-theme toggle
Brand ships `html[data-theme="light"]`. Since our tokens are centralized, we can add
a `[data-theme="light"]` override block + a persisted toggle in the topbar for near-zero
extra cost. Defer unless wanted; dark-first is the agreed default.

## File-by-file change list

| File | Change |
|---|---|
| `src/main/resources/admin/style.css` | Rewrite `:root` tokens; dark-correct shadows, grid, pills, scrollbar, select arrow, topbar bg, color-scheme; `--sans` → Geist; optional `.auth` + `[data-theme=light]` |
| `src/main/resources/admin/index.html` | Font link Hanken→Geist |
| `src/main/resources/app/index.html` | Font link Hanken→Geist |
| `src/main/resources/backoffice/index.html` | Font link Hanken→Geist |
| `src/main/resources/app/app.js` | Re-skin `renderLogin()` markup |
| `src/main/resources/backoffice/app.js` | Re-skin `renderLogin()` markup |
| `src/main/resources/admin/app.js` | Add branded login flow (new) |
| `docs/architecture.md` | Note the shared-stylesheet design system if not already |

No Kotlin/route changes required (static assets served generically; auth endpoints
already exist).

## Risks & gotchas

- **Hardcoded light values in component rules.** Several rules embed literal
  `oklch(0.85 …)` / `oklch(0.20 …)` instead of vars (pill borders, grid lines,
  scrollbar, shadows, select arrow). These won't follow the token swap — they're
  enumerated in Phase 2 and must be edited individually.
- **`.btn--primary` inversion** flips meaning on dark (becomes a light button).
  Decide accent-vs-light primary before shipping (Phase 2).
- **Status-color contrast.** The `-soft`/`-ink` triplets are the most finicky on
  dark; eyeball pills (`ok/warn/bad/info/accent`) and overdue/paid/draft row
  markers after the swap.
- **Geist availability/FOUT.** Confirm the Google Fonts Geist URL loads; keep
  `preconnect`. Fallback stack already present.
- **Caching.** `style.css` has no cache-busting query. After deploy, a hard refresh
  may be needed; consider appending `?v=2` to the `<link href>` in the three heads.

## Verification

1. `./gradlew build` (no code logic changes, but compiles resources into the JAR).
2. `run local` (MongoDB + Ktor on :8080); `create-mocks` if data is empty.
3. Visit `/admin`, `/app`, `/backoffice` and the login on each. Check:
   - background `#0b0d0f`, yellow accent, Geist headings, JetBrains-mono numerals
   - tables, pills (all status variants), drawers, forms, line items, toasts, confirm
   - focus rings (accent-soft glow), hover states, the body grid texture
   - mobile breakpoint (`max-width: 920px`)
4. Side-by-side a couple of screens against the `thebots.lab` brand site for fidelity.

## Rollback

Single-commit, single-stylesheet change → `git revert` the redesign commit, or
restore `style.css` from git and revert the three font-link edits.

## Open questions for Rodrigo

1. **CRM Admin login (Phase 4):** add a real login page to `/admin`, or keep the
   current redirect-to-backoffice behavior? (Plan assumes: add it.)
2. **Primary button:** accent-yellow CTA, or keep the high-contrast light button as
   primary with accent reserved for highlights? (Plan recommends: accent CTA.)
3. **Light-theme toggle (Phase 6):** ship now, or dark-only for v1? (Plan: defer.)
