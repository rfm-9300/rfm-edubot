# UI Design System — agent rules

Parent repo `AGENTS.md` has the personal-wiki section. This file is UI-only; still read
`/Users/rodrigomartins/projects/my-wiki/wiki/index.md` before substantial design work
(`wiki/concepts/thebots-design-system.md`).

Follow these rules for any change under `src/main/resources/admin/`, `app/`, or `backoffice/`. Widget and legal pages have their own exceptions at the bottom.

## Must

- Use existing classes from [components.md](components.md). Prefer composition over new CSS.
- Style with tokens from [tokens.md](tokens.md) (`var(--accent)`, `var(--ink)`, `var(--r-lg)`, …).
- Put every user-facing string through i18n ([i18n.md](i18n.md)). Placeholder and “coming soon” copy counts.
- Escape dynamic values with `escapeHTML()` in JS templates.
- Keep the shell: `aside.sidebar` + `main.main` > `header.topbar` + `section.view`.
- Load `/admin/style.css` and `/admin/theme.js` on every dashboard page.
- Load catalogs + `i18n.js` before the page’s `app.js`.
- Keep BEM-style names already in use (`block__elem--mod`).
- After adding a token, class, or pattern, update this `design-system/` folder.

## Must not

- Do not add a second stylesheet for admin / app / backoffice.
- Do not hardcode hex, `oklch()`, or `rgb()` in component rules. Add a token on `:root` (and `html[data-theme="dark"]` if the dark value differs).
- Do not introduce a new font family. Stack is Outfit + Nunito + JetBrains Mono.
- Do not hardcode Portuguese (or any locale) in markup or JS render functions.
- Do not copy widget (`.tbl-*`) styles into the dashboards, or dashboard classes into the widget.
- Do not restyle legal pages to look like the CRM unless explicitly asked.
- Do not use inline `style=""` for colors, type, or spacing that tokens already cover.
- Do not invent a new button / pill / modal primitive when `.btn`, `.pill`, `.drawer`, `.confirm` exist.
- Do not skip the dark-theme check. If a rule assumes light (shadow, border, SVG stroke), add an `html[data-theme="dark"]` override.

## Decision tree

```
Need a control?
  exists in components.md? → use it
  close variant (size/tone)? → existing modifier (--sm, --ghost, --danger, --primary)
  genuinely new? → add to style.css with tokens, then document in components.md

Need a color?
  semantic token exists (--accent, --ok, --bad, …)? → use it
  else → add --name on :root AND html[data-theme="dark"], then use var(--name)

Need copy?
  add key to catalog.en.js, catalog.pt.js, catalog.es.js
  HTML: data-i18n / data-i18n-placeholder / data-i18n-aria-label
  JS: I18N.section('app'|'admin'|'backoffice') or I18N.t('…')
```

## Checklist before finishing a UI change

- [ ] Reused shared classes; no one-off palette
- [ ] Light and dark both readable (contrast on pills, buttons, empty states, tables)
- [ ] `max-width: 920px` does not overflow the shell
- [ ] Strings in all three catalogs; no raw UI literals in JS/HTML
- [ ] Dynamic text escaped
- [ ] Focus states use `border-color: var(--accent)` + `box-shadow: 0 0 0 4px var(--accent-soft)`
- [ ] Destructive actions go through `.confirm`, not `window.confirm`
- [ ] `design-system/` updated if tokens/classes/patterns changed

## Exceptions

**Website widget** (`src/main/resources/widget/`): isolated embed. Classes prefixed `tbl-`. Accent is `--tbl-accent` (host override, default `#2563eb`). Do not load `/admin/style.css` into a host page.

**Legal** (`src/main/resources/legal/`): standalone documents with their own inline CSS. Keep them readable and boring.

**Mobile** (`mobile/`): Kotlin Multiplatform. This design system does not apply.
