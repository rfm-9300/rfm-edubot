---
name: ui-design-system
description: >-
  Apply the thebots.lab UI design system so admin, app, and backoffice stay visually consistent.
  Use when adding or changing HTML, CSS, JS, components, pages, themes, buttons, forms, tables,
  drawers, or copy in src/main/resources/admin, app, backoffice, widget, or legal; or when the
  user mentions UI, design system, restyle, frontend, dashboard, or i18n strings.
---

# UI Design System

Read and follow **`design-system/AGENTS.md`** before editing any dashboard UI.

Then open only what you need:

- Tokens (color, type, radius): `design-system/tokens.md`
- Class catalog: `design-system/components.md`
- Page recipes: `design-system/patterns.md`
- Strings: `design-system/i18n.md`

Implemented CSS: `src/main/resources/admin/style.css`. Theme: `admin/theme.js`.

Do not create a new stylesheet for `/admin`, `/app`, or `/backoffice`. Do not hardcode colors or user-facing strings. If you add a token or component, update `design-system/` in the same change.
