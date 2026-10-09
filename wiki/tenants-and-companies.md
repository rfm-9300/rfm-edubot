---
updated: 2026-10-09
---

# Tenants and companies

How a customer's account (tenant) holds one or more companies, and what that means for data, users, tokens and the backoffice lifecycle. Which modules a tenant gets, and why channels are optional, is in [overview.md](overview.md).

## Companies inside a tenant (shipped 2026-09-29, commit `46dd2c8`)

Some customers run two businesses and want their data kept apart under one login. Rodrigo's choices:
the tenant's administrators add companies themselves in Settings → Companies, up to a limit only the
backoffice sets ("Companies allowed", `maxCompanies`, 1 to 20, first company included), and sign-in
always opens the first company. Design decisions that matter later:

- **A company is a `Tenant` document.** Extra companies carry `parentTenantId` pointing at the first
  company, and `Tenant.primaryTenantId` is the first company's id for all of them. Every existing
  `tenantId` scope (CRM, conversations, channels, persona, modules, PDF template, token budget) is
  therefore per company with no data-layer change. This was chosen over adding a `companyId` to every
  collection.
- **Users hang off the first company.** `dashboard_users.tenantId` always points at it, and
  `DashboardAccessPolicy` lets a user open any company whose `primaryTenantId` equals it. So there
  was no user migration, and rolling back the image still works for the first company. Every user of
  a tenant opens all its companies: there are no per-company memberships or roles yet. The
  backoffice users drawer resolves any company's slug to the first company and is hidden on extra
  companies.
- **Switching trades the token.** `POST /app/api/companies/{id}/switch` answers a token for the other
  company with the same subject, role, type and expiry, so switching never extends a session
  (operators opening the dashboard can switch too). The browser stores it and reloads rather than
  resetting the global `state` by hand. The sidebar brand is the switcher (`button.brand--switch`,
  disabled and unchanged-looking for one company).
- **A new company starts empty** (no channels, no document template) with the first company's
  modules, rate limits, token budget, model, locale and timezone copied once; afterwards the
  backoffice edits each company's modules separately. Each company has its own monthly token budget,
  so the limit also caps LLM spend per tenant. Creating one needs `TENANT_ADMIN` (the first place a
  role is enforced) and the `settings` module, and the limit check runs under an in-process
  per-tenant lock (single app instance, like bookings).
- **Lifecycle cascades from the first company** in the backoffice: suspend, activate and delete apply
  to its non-deleted companies, while changing an extra company touches only that one. A deleted
  company frees a slot. `/app` has no delete or rename for companies.
- **Delete is a soft delete, undone by Restore** (shipped 2026-09-30, `9677e9e`). Deleting stamps
  `deletedAt`, the same instant on a first company and the companies it takes down, and restore
  (`POST /admin/api/tenants/{slug}/restore`) brings back only companies sharing that instant. Why:
  without it, restoring could not tell cascade-deleted companies from ones deleted on purpose earlier.
  Tenants deleted before the field existed have none, so a legacy restore brings back every legacy
  deleted company. A company can't return while its first company is deleted or full. Deleted tenants
  keep their slug and WhatsApp number (both unique indexes), so create answers `409 slug_taken` with
  the holder's status. The backoffice hides deleted tenants behind a Deleted chip (Restore only).
- The mobile app is unchanged: it signs in to the first company and has no switcher.

Open gaps: two tenants that already exist separately can't be merged into one (that would need
attaching a tenant and moving its users); no per-company access for staff; a company's slug is the
first company's slug plus its name and never changes.

Production rollout: CI green (196 tests) and deployed the same day; prod healthy on `46dd2c8`, the
`parentTenantId_1` index created at startup, no tenant document touched (no data migration), Mongo
not bounced. Every tenant keeps `maxCompanies` = 1 until it is raised in the backoffice.
