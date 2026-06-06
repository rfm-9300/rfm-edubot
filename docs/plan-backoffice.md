# Backoffice Plan — Platform Tenant Management UI

> Companion to `plan-b-multitenant.md`. That plan makes the **backend** multi-tenant; this plan
> covers the **operator-facing web UI** for managing tenants (the bots/clients). Read the
> multi-tenant plan first — this one depends on its Phase 1/3/4.

---

## 1. The two surfaces (this is the key mental model)

There are **two distinct web UIs**, with two different audiences, even though today only one
exists:

| | **Backoffice** (NEW — this plan) | **CRM Admin** (EXISTS — `src/main/resources/admin/`) |
|---|---|---|
| Who uses it | You / your company (the platform operator) | A bot's own CRM data manager (still you, per client) |
| Scope | **All tenants** — the platform | **One tenant** at a time |
| Manages | Tenants/bots: create, configure, suspend, model, rate limits | That tenant's clients, quotes, invoices, catalog |
| Today | Does not exist | "ISAL · Painel de Gestão" (`index.html`/`app.js`/`style.css`) |
| Route | `/backoffice` | `/admin` (becomes tenant-scoped) |
| API | `/admin/api/tenants/*` | `/admin/api/tenants/{slug}/{clients,quotes,...}` |

**Critical realization (your point):** the existing `/admin` UI is branded "ISAL" and assumes a
single company. Under multi-tenant it becomes **one tenant's CRM view**, reached *from* the
backoffice. The backoffice sits **above** it.

```
        ┌────────────────────────── Backoffice  /backoffice ──────────────────────────┐
        │  Login → Tenants list → [create / edit / suspend / reload] a tenant          │
        │                                   │                                          │
        │                          "Open CRM" on a row                                 │
        └───────────────────────────────────┼──────────────────────────────────────────┘
                                             ▼
        ┌──────────────── CRM Admin  /admin?tenant=<slug>  (existing UI, scoped) ───────┐
        │  Clientes · Orçamentos · Faturas · Items Padrão   — for that one tenant       │
        └──────────────────────────────────────────────────────────────────────────────┘
```

### Decision: separate surface vs one merged app

- **(A) Two surfaces, shared login (recommended).** `/backoffice` = platform; `/admin` = tenant
  CRM with a tenant context in the URL. Clean separation, matches the two-API split, the
  existing CRM UI barely changes (it just learns to read a `tenant` slug). One JWT covers both.
- **(B) One merged SPA** with a tenant switcher in the header and a "Platform" section. More
  convenient for a solo operator, but couples the two concerns and forces a bigger rewrite of
  the existing app.

This plan assumes **(A)**.

---

## 2. Tech approach — match what's already there

Keep the existing stack so there's no new toolchain:

- **Vanilla JS SPA, no build step.** Same pattern as `admin/app.js` (a small `api()` fetch
  helper, client-side `state`, hash-routed tabs, a drawer for forms, a toast). Do **not**
  introduce React/Vite unless you want a build pipeline — the current app deliberately has none.
- **Reuse `admin/style.css`** (496 lines, already a complete design system: sidebar, topbar,
  drawer, pills, toast, confirm dialog). The backoffice gets the same look for free.
- **Served as static resources** from `src/main/resources/backoffice/`, mounted by a Ktor route,
  exactly like `AdminRoutes.kt:43-58` serves `/admin/`.
- **Auth:** the JWT login from multi-tenant Phase 4. The backoffice is the *first* thing that
  must be behind auth (it can create/suspend bots).

---

## 3. Backend dependencies (from the multi-tenant plan)

The backoffice is a thin frontend over endpoints defined in `plan-b-multitenant.md`. It cannot
start until these exist:

| Needs | From MT plan |
|---|---|
| `Tenant` model + `tenants` collection + indexes | Phase 1 |
| `TenantRepository` + `TenantRegistry` | Phase 1 / Phase 3 |
| `POST /admin/auth/login` + `authenticate("admin-jwt")` | Phase 4 |
| `GET/POST/PUT/DELETE /admin/api/tenants` + suspend/activate/reload | Phase 4 |
| Tenant-scoped CRM API `/admin/api/tenants/{slug}/...` | Phase 4 |

This plan **adds two backend pieces** the MT plan didn't strictly need:

- **`GET /admin/api/tenants/{slug}/stats`** — lightweight per-tenant counts for the dashboard:
  `{ users, conversations, messages, quotes, invoices, lastMessageAt }`. Implemented with
  `countDocuments(tenantId filter)` on each collection — no new storage.
- **Agent type catalog** — `GET /admin/api/agent-types` returning the registered agent types
  (just `["CRM_V1"]` until Phase 5) so the "create tenant" form's dropdown isn't hardcoded.

---

## 4. Screens

### 4.0 Login (`/backoffice` when unauthenticated)
- Single password field → `POST /admin/auth/login` → store JWT in memory + `localStorage`.
- All `api()` calls send `Authorization: Bearer <jwt>`; on 401 → bounce to login.
- (The existing `admin/app.js` `api()` helper at `app.js:7` needs the same Bearer header added.)

### 4.1 Tenants list (default view)
Table, one row per tenant:

| Column | Source |
|---|---|
| Name | `tenant.name` |
| Slug | `tenant.slug` |
| Phone number ID | `tenant.phoneNumberId` |
| Agent | `tenant.agentType` |
| Status | pill: ACTIVE / SUSPENDED / DELETED (reuse `.pill` styles) |
| Messages / Last active | `stats.messages` / `stats.lastMessageAt` |
| Actions | Open CRM · Edit · Suspend/Activate · Reload |

- "Novo bot/cliente" primary button (mirrors the existing `#btn-new`).
- Search box filters the list client-side (same `#search` pattern).

### 4.2 Create tenant (drawer form)
Fields:
- **Name** (free text) → `name`
- **Slug** (auto-suggested from name via the existing `slugify()` at `app.js:101`, editable)
- **Phone number ID** (the one per-tenant WhatsApp value) → `phoneNumberId`
- **Agent type** (dropdown from `/admin/api/agent-types`, default `CRM_V1`)
- **Rate limits** (per hour / per day, prefilled 30 / 200)
- **Model override** (optional; blank = use platform default)

Submit → `POST /admin/api/tenants`. On success: row appears; pipeline builds lazily on the
tenant's first inbound message (no restart). Show a toast with the next step:
> "Bot criado. Liga o número {phoneNumberId} à tua app Meta e aponta o webhook." (reminder, not
> an automated step — linking the number in Meta is manual.)

### 4.3 Edit tenant (drawer form)
- Editable: name, agent type, rate limits, model override.
- `phoneNumberId` and `slug` shown read-only (changing them is effectively a new bot — avoid
  foot-guns; if ever needed, do it deliberately, not in the common edit path).
- Submit → `PUT /admin/api/tenants/{slug}` → registry refresh + pipeline evict (per MT Phase 4).

### 4.4 Lifecycle actions
- **Suspend / Activate** → `POST .../suspend|activate`. Suspended tenants stop processing
  (webhook skips them per MT Phase 3.4). Confirm dialog (reuse `#confirm`).
- **Delete (soft)** → `DELETE .../{slug}` → status DELETED + registry remove + evict. Danger
  confirm.
- **Reload pipeline** → `POST .../reload` (evict cache; rebuilds on next message). Useful after
  changing model/limits or if a tenant misbehaves.

### 4.5 Open CRM (bridge to the existing UI)
- Row action navigates to `/admin/?tenant=<slug>`.
- The existing `admin/app.js` learns to read the `tenant` query param and prefix its API calls
  from `/admin/api/...` to `/admin/api/tenants/<slug>/...` (the `API_BASE` at `app.js:5`).
- Replace the hardcoded "ISAL" branding (`index.html:23-24`) with the tenant's name (fetched
  from `/admin/api/tenants/<slug>`), and add a "← Backoffice" link so you can get back.

---

## 5. Changes to the EXISTING admin UI (in scope here)

The existing CRM SPA is not thrown away — it's retargeted to be tenant-scoped:

1. **`API_BASE`** (`app.js:5`) becomes tenant-aware: read `?tenant=` and build
   `/admin/api/tenants/<slug>`. If no slug → redirect to `/backoffice`.
2. **Auth header** added to `api()` (`app.js:7`) — same JWT as the backoffice.
3. **Branding** (`index.html:6,23-24`, "ISAL / Isal · Painel de Gestão") becomes dynamic from
   the tenant name, plus a back-link to `/backoffice`.
4. Everything else (clients/quotes/invoices/items views, drawer, CSV export) is unchanged — the
   backend just scopes it to one tenant.

> If you ever pick merged-app Option (B), these changes instead become a header tenant-switcher
> inside one SPA. Same data, different shell.

---

## 6. Files

```
src/main/resources/backoffice/
  index.html      NEW  shell: sidebar (Tenants), topbar, drawer, confirm, toast — clone admin/index.html structure
  app.js          NEW  api() w/ JWT, login, tenants list, create/edit, lifecycle, stats, "Open CRM"
  (style.css)     REUSE  link to /admin/style.css (or copy if you want them to diverge)

src/main/resources/admin/
  app.js          CHANGED  tenant-aware API_BASE + JWT header + dynamic branding (see §5)
  index.html      CHANGED  dynamic title + back-link

src/main/kotlin/com/rfm/edubot/admin/
  BackofficeRoutes.kt   NEW  GET /backoffice, /backoffice/{asset}  (mirror AdminRoutes.kt:43-58, behind auth)
  TenantAdminRoutes.kt  NEW  (from MT Phase 4) tenant CRUD + /stats + /agent-types
  AuthRoutes.kt         NEW  (from MT Phase 4) login
```

---

## 7. Phasing (do AFTER multi-tenant Phase 4 backend is in)

```
BO-0  Prereqs: MT Phase 1 (tenant model) + Phase 3 (registry/routing) + Phase 4 (auth + tenant CRUD API)
BO-1  Login screen + JWT plumbing in api(); guard /backoffice route
BO-2  Tenants list (read-only) + /stats endpoint           ← first useful screen
BO-3  Create tenant form (+ /agent-types)                  ← onboarding without curl
BO-4  Edit + lifecycle (suspend/activate/delete/reload)
BO-5  Retarget existing admin to tenant scope + "Open CRM" bridge + dynamic branding
```

Until BO-3 ships you onboard clients with `curl` against the Phase 4 API — so the backoffice
never blocks going live. Build the UI once the API is proven against your first real client.

---

## 8. Out of scope (note for later, don't build now)

- Multiple operator accounts / roles (single shared admin password for now — MT Phase 4).
- Rich analytics (token spend, cost per tenant, charts). Start with plain counts in `/stats`.
- Self-service client signup. This backoffice is operator-only.
- Automating the Meta side (linking a number, webhook subscription) — stays manual; the UI only
  reminds you.
```
