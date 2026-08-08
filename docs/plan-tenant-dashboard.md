# Tenant Dashboard Plan — the per-client product surface

> Companion to `plan-b-multitenant.md` (backend) and `plan-backoffice.md` (operator UI). This
> plan needs MT Phase 1 (isolation) + Phase 4 (auth infra). It **supersedes** the "Open CRM"
> bridge in the backoffice plan (see §10).

> Evolve today's single-company CRM admin (`/admin`, "ISAL · Painel de Gestão") into the
> **dashboard every tenant logs into to see and manage their chatbot's data**.

---

## 0. Decisions locked

- **Audience: clients log in themselves.** Each tenant has its own user account(s), scoped to
  *only* their tenant. The operator (you) can still open any tenant's dashboard from the
  backoffice via impersonation.
- **Scope: Overview, Conversations, Contacts, CRM, Bot self-service settings.**
- **Structure: the dashboard is the container; CRM is one module inside it.** The chatbot is the
  product; conversations/contacts/metrics are universal to every tenant. Persona and CRM modules
  are enabled per tenant by the operator.

---

## 1. The non-negotiable: tenant isolation comes from the token

Because clients log in, this is now a real data-isolation boundary, not a convenience:

> The dashboard data API resolves `tenantId` **from the authenticated principal (the JWT)**,
> never from a URL path, query param, or request body. A tenant user literally cannot express
> "give me another tenant's data" — there's no input that carries a tenant id.

This differs from the operator/backoffice path, which *is allowed* to cross tenants. Two
principal types:

| Principal | tenantId source | Can cross tenants? | Used by |
|---|---|---|---|
| **Tenant user** | bound in their JWT | No | the dashboard (clients) |
| **Operator** | none (or chosen via impersonation) | Yes | backoffice |

Depends on MT Phase 1 (every repo scoped by `tenantId`). The dashboard supplies the tenantId from
the token.

---

## 2. Auth model

### 2.0 Three actors — pin the terminology

The word "user" is overloaded. There are **three** distinct kinds of person, and only two are
login accounts. Today the code has **none** of this (admin UI is unauthenticated; the only
"user" is the WhatsApp end-user record).

| Actor | Login? | Scope | In code |
|---|---|---|---|
| **Operator** = you / TheBotsLab | Yes | **All** tenants | backoffice; JWT `typ:"operator", role:PLATFORM_ADMIN`, **no** `tenantId` |
| **Tenant user** = the client | Yes | **One** tenant (their own) | this dashboard; JWT `typ:"tenant", tenantId, role:TENANT_ADMIN\|TENANT_MEMBER`; `dashboard_users` collection |
| **End-user** = the WhatsApp person chatting with the bot | **No** | — | `users` collection (`waId`, `UserStatus`); shown in the **Contacts** module; never authenticates |

Two independent axes of "admin vs normal":
- **Operator vs Tenant user** — *which tenants you can touch.* Operator crosses all tenants
  (backoffice); a tenant user is locked to one `tenantId` derived from their token (see §1).
- **TENANT_ADMIN vs TENANT_MEMBER** — *power within one tenant.* Admin manages team + settings;
  member uses the dashboard but can't manage users. A role **inside** a single tenant, orthogonal
  to the operator/tenant split.

> Don't conflate **end-user** with **tenant user**: they share the word "user" but are unrelated.
> The end-user is a WhatsApp contact (a data row, no login); the tenant user is a dashboard
> account. The Contacts module lists end-users; `dashboard_users` holds tenant users.

### 2.1 New collection: `dashboard_users`
```
{ _id, tenantId, email (globally unique), passwordHash (bcrypt),
  role: "TENANT_ADMIN" | "TENANT_MEMBER", status: "ACTIVE"|"DISABLED",
  createdAt, lastLoginAt }
```
- Index unique(`email`) globally → login is just email+password, no slug; the user record carries
  `tenantId`. (One email = one tenant; multi-tenant-per-user deferred.)
- Index (`tenantId`) for listing a tenant's users.

### 2.2 Login + JWT
```
POST /app/auth/login  { email, password }
  → look up by email → verify bcrypt → issue JWT
  claims: { sub: userId, tenantId, role, typ: "tenant", exp }
```
- Operator tokens (MT Phase 4) carry `{ role: PLATFORM_ADMIN, typ: "operator" }`, no tenantId.
- A Ktor `authenticate("dashboard")` provider accepts tenant tokens; a wrapper extracts
  `tenantId` from the principal and builds tenant-scoped repos per request.

### 2.3 Operator impersonation (open a client's dashboard)
```
POST /admin/api/tenants/{slug}/impersonate   (operator-authed)
  → mints a short-lived dashboard JWT scoped to that tenantId, flagged typ:"operator-imp"
  → opens /app with that token
```
The SPA needs no special-casing. Flag `operator-imp` for audit / optional read-only.

### 2.4 Who creates tenant users?
- Operator creates the first `TENANT_ADMIN` when onboarding (backoffice "Users" action per
  tenant).
- `TENANT_ADMIN` invites/disables `TENANT_MEMBER`s within their own tenant (Settings → Team).
- Password reset: minimal first (operator-set temp password); email reset later.

---

## 3. Module architecture

Shell (login, nav, tenant name/branding) + modules. Visibility is driven by the tenant's effective
`enabledModules`:

```
core (every tenant):  Overview · Conversations · Contacts · Settings
optional:             Persona · Clients · Quotes · Invoices · Catalog
```

The backend exposes effective modules via `GET /app/api/me` →
`{ tenant:{name,branding}, user:{email,role}, modules:[...] }`. The server still enforces gating
per route; client navigation visibility is not an authorization boundary.

---

## 4. Modules (specs + backend gaps)

Most CRM read/write already exists; the **core** modules need new repository read methods —
today's repos only support the bot's hot path (findByWaId / findOrCreate / lastN).

### 4.1 Overview (`/app` landing)
- KPIs: messages (today / 30d), active contacts, conversations, open quotes / unpaid invoices
  (CRM), token usage / est. cost. Recent activity feed.
- Backend: `GET /app/api/overview` — `countDocuments(tenant filter)` per collection + per-day
  aggregation on `messages.createdAt`. No new storage.

### 4.2 Conversations (read-only first)
- List conversations (paginated, search by contact); open one → full message thread.
- Backend gaps (new methods on tenant-scoped repos):
  - `ConversationRepository.list(page, query)` (today only findByWaId/findById/findOrCreate).
  - `MessageRepository.threadByConversation(conversationId, asc paging)` (today only `lastN`).
- "Reply from dashboard" (human handoff) is out of scope for v1.

### 4.3 Contacts (the bot's end-users)
- List `users` for the tenant (name, waId, last seen, status, search).
- Actions: block/unblock → `UserStatus.BLOCKED` (already honoured at `MessagePipeline.kt:61`),
  open conversation.
- Backend gaps on `UserRepository` (today only findByWaId/findOrCreate): `list(page, query)`,
  `setStatus(userId, status)`.

### 4.4 CRM module (mostly exists — rescope + gate)
- Existing clients/quotes/invoices/catalog views, unchanged in function.
- Changes: (a) drop slug from API base — tenant from token (§1); (b) require the matching enabled
  module on every route; (c) branding from tenant profile (§5).

### 4.5 Settings (self-service)
- **Business profile** → name, email, phone, address, tax id, logo. **Feeds PDF generation.**
- **Catalog / pricing** → existing `standard_items` editor (already CRUD + tenant-scoped post MT
  Phase 1).
- **Team** → invite/disable tenant users (§2.4).
- **Bot tweaks (guarded)** → optional small "extra instructions" appended to the system prompt,
  greeting, business hours. Core prompt + model + rate limits stay **operator-only**.

---

## 5. Business profile + PDF branding ✅

Per-tenant `DocumentTemplate` on the `Tenant` document (company name, tagline, tax ID, email,
phone, address, quote/invoice titles, payment terms, terms text, footer, logo path). Edited in
Dashboard → Settings → Quote & invoice template (`GET/PUT /app/api/settings/document-template`,
logo upload/delete). `PdfGenerator` accepts the template for all quote/invoice PDFs (dashboard,
admin CRM, WhatsApp pipeline).

---

## 6. Frontend approach

- Vanilla JS, no build step; reuse `admin/style.css` (sidebar/topbar/drawer/pills/toast/confirm).
- **Evolve the existing `admin/` SPA into the dashboard** rather than rewrite: extract CRM views
  into a "CRM module", add a client-side module router + nav, add login, then add Overview /
  Conversations / Contacts / Settings.
- `api()` (`app.js:7`) gains `Authorization: Bearer` + tenant-implicit base (`/app/api/...`, no
  slug); on 401 → login.

---

## 7. Routing / naming

| Surface | Route | Auth | Who |
|---|---|---|---|
| Tenant Dashboard (this) | `/app` | tenant JWT | clients |
| Backoffice | `/backoffice` | operator JWT | you |
| old CRM admin `/admin` | retired → folded into `/app` | — | — |

(Naming is preference — `/dashboard` works too.)

---

## 8. Backend additions summary

| Area | New |
|---|---|
| Auth | `dashboard_users`; `/app/auth/login`; `authenticate("dashboard")` + tenantId-from-principal; `/admin/api/tenants/{slug}/impersonate` |
| Identity | `GET /app/api/me` (tenant, user, modules, branding) |
| Overview | `GET /app/api/overview` (counts + per-day message aggregation) |
| Conversations | `ConversationRepository.list()`; `MessageRepository.threadByConversation()`; routes |
| Contacts | `UserRepository.list()` + `setStatus()`; routes |
| Settings | tenant business profile storage + CRUD; `PdfGenerator` takes profile; team CRUD |
| CRM | rescope existing routes to tenant-from-token; gate by effective modules |

---

## 9. Phasing (after MT Phase 1 + 4)

```
DASH-0  Prereqs: MT Phase 1 (tenantId isolation) + MT Phase 4 (JWT/auth infra)
DASH-1  Auth: dashboard_users + tenant login + tenantId-from-token scoping + impersonation
DASH-2  Shell: SPA shell (login, nav, module router) + /app/api/me + effective modules
DASH-3  CRM module: rescope existing CRM to tenant-from-token, gate by module   ← reuses most code
DASH-4  Overview module + /overview
DASH-5  Conversations module (read) + repo list/thread methods
DASH-6  Contacts module + user list/setStatus
DASH-7  Settings: business profile (+ PdfGenerator branding), catalog, team, guarded bot tweaks
```

DASH-1/2 are the real new work (auth + shell). DASH-3 is mostly moving existing code. Ship
DASH-1→3 to get clients logging into their CRM, then layer 4→7.

---

## 10. Relationship to the other plans (reconcile)

- **Supersedes** `plan-backoffice.md` §4.5 ("Open CRM") and §5 ("retarget existing admin"). The
  existing `/admin` no longer becomes an operator-navigated `?tenant=slug` view — it becomes the
  client-facing `/app` dashboard, reached by the operator via **impersonation** (§2.3). The
  backoffice still creates tenants and now also creates the first tenant admin user.
- **Depends on** `plan-b-multitenant.md`: Phase 1 (isolation — mandatory for client login),
  Phase 4 (JWT machinery, extended with a tenant principal).
- **Capability source:** the operator-managed `Tenant.enabledModules` selection.

---

## 11. Out of scope (note, don't build now)

- Human handoff / replying to end-users from the dashboard (Conversations is read-only v1).
- Email-based password reset + invitations (start operator-set temp passwords).
- Per-user granular permissions beyond TENANT_ADMIN / TENANT_MEMBER.
- Client-editable core system prompt / model / rate limits (operator-only).
- Billing / usage-based invoicing of tenants.

---

## 12. Security checklist

- Every `/app/api/*` handler derives `tenantId` from the principal; no handler reads a tenant id
  from path/query/body.
- A Tenant A token returns 404/empty for any id belonging to Tenant B — test cross-tenant ids.
- Impersonation tokens short-lived and flagged (`operator-imp`).
- Passwords bcrypt-hashed; login rate-limited; generic error on bad credentials.
- Server enforces effective-module gating on every route (don't trust client nav).
