---
updated: 2026-10-09
---

# Catalog, Serviços and bookings

The catalog of services and materials, the Serviços rows that record work done (and get invoiced), the shared line items editor, and bookings, which book catalog services. Invoicing itself is in [billing-and-pdfs.md](billing-and-pdfs.md).

## Catalog titles and codes, supplier type (shipped 2026-10-01, `3792532`)

Rodrigo asked for a title and an id/code on every catalog item, and a "type" on suppliers. His
choices: codes numbered automatically per type (`SRV-001`, `MAT-001`, like `CLT-001`), editable,
with existing items numbered on the next start; the supplier type is free text with suggestions from
the types already in use, like an employee's role. Decisions that matter later:

- **Two identifiers on purpose.** The internal `id` (`srv-<slug>` / `mat-<slug>`) stays the key
  that Serviços rows (`catalogItemId`) and bookings point at, so it never changes. The server now
  generates it from the title (the form's "ID · slug" field is gone); an id a client sends (the
  mobile app) is still used, with `409 id_taken` on a clash. The `code` is what the tenant sees and
  may change: left empty, it is numbered per type from `crm.sequences` (`catalog_srv_code`,
  `catalog_mat_code`), skipping codes typed by hand, and a partial unique index `(tenantId, code)`
  keeps it unique per tenant (`409 code_taken`). Numbers are not reused after a delete.
- **An empty description stores the title.** The title is the name everywhere (lists, pickers, a
  booking's service name, the AI's `list_standard_items`); the description is optional detail. An
  item without one keeps its title in `description`, so the previous release, the mobile app (which
  still sends and shows only `description`) and anything else reading it still get a name. Readers
  treat a description equal to the title as none (`StandardItem.details()`, `catalogDetails()` in
  `app.js`). Chosen over moving text out of `description`, which would break a rollback.
- **Document lines** get "title - description": the classic PDF's `splitItem` prints the part after
  " - " under the title.
- **`CatalogItemBackfill`** runs at startup, before the booking migration: items without a title get
  their description as title, items without a code the next free one, per tenant in creation (`_id`)
  order. It writes only missing fields. On the local DB's first start it filled 25 items.
- `StandardItem.title` has no default value, so `encodeDefaults = false` never drops it.
- **Supplier type** (`crm.suppliers.type`): `PATCH` keeps it when omitted and clears it on an empty
  string (the rule of a client's email/NIF/notes), so a stale browser tab can't wipe it; 60 chars max
  (`type_too_long`). The directory shows a Type column and an "All types" filter that treats types
  differing only in case or spacing as one; the record card shows it.
- Left as is: the mobile app's catalog list still shows `description` (the title, unless the item has
  its own description); codes wrap at the hyphen on phone-width tables like every `.tbl .id` column,
  a shared rule that also lays out the backoffice's backup file names.

Verified locally: 290 tests green (new `CatalogItemsTest`, `SupplierRepositoryTest`), API edge cases
by curl, and headless-Chrome screenshots of the catalog list and forms, the code-clash toast, a quote
line from the catalog, and the suppliers list, filter and record, in light, dark and pt-PT.

Production rollout (2026-10-01): a backup was taken right before the deploy
(`~/whatsapp-bot/backups/mongo-20261001T095102Z.archive.gz`, also in GCS). CI deployed `3792532`,
Mongo was not bounced, and on first start the backfill gave all 48 catalog items a title and a code
(three tenants, each numbered from `SRV-001`/`MAT-001`, no duplicates, descriptions unchanged). No
supplier had a type yet (5 suppliers). The `backup-mongo.sh` stdin gotcha seen on the way is on
[ops-and-deploy.md](ops-and-deploy.md).

### Catalog code format (shipped 2026-10-06, `8c889ca`)

Rodrigo's rule: a catalog code is **three letters, a dash and digits** (`^[A-Z]{3}-[0-9]+$`, so
`SRV-001`, `MAT-23`, `TBL-8`). He first said two letters, then corrected to three and chose to keep
the dash, which made today's automatic codes already valid.

- `StandardItemRequest.error()` answers `400 code_invalid` for any other typed code (create and
  edit, `/app` and the backoffice twin). Typed lowercase is saved in capitals, and a blank code is
  still numbered automatically. The `/app` form shows the format under the field and checks it before
  sending. The mobile app doesn't know `code_invalid` yet and shows a generic error.
- `CatalogItemBackfill` now treats a code that doesn't match as missing: at startup it gives the item
  the next free `SRV-`/`MAT-` code for its type, in creation order, and logs every `old became new`.
  So the rule also holds for data written before it or behind the API's back.

- Prod check on 2026-10-06: one company broke the rule, with 88 hand-typed codes. Rodrigo chose to renumber them by type in creation order, which the backfill does on the first deploy. Codes aren't stored on quotes, invoices, Serviços rows or bookings (they keep the `id` or a copy of the title), so no document changes.

Production rollout (2026-10-06): backup right before; CI deployed `8c889ca` without bouncing Mongo; the backfill logged 88 `became` lines and `Gave 88 catalog items a title or a code`, every renumbered code matched the preview, no tenant has a non-matching code, 0 `ERROR` lines.

## Serviços rows

A Serviços row (`crm.client_services`) records work done for a client and is what invoices bill. A completed booking creates one (below), an approved employee submission becomes one ([employee-portal-and-time-clock.md](employee-portal-and-time-clock.md)), and staff add them by hand.

- **A Serviços row can hold several lines** (since `b6448f9`, 2026-10-01; `crm.client_services.items`, the quote `LineItem` shape)
  and totals their sum. Its `quantity`/`unit`/`unitPriceCents` summarize the lines (a single line's
  own, or 1 × the sum), so the previous release, Home's `totalCents` sums and the booking code keep
  working. A `PATCH` without `items` keeps them and ignores those three fields, because the dashboard's
  `servicePatch` (cancel/reopen) and the booking `unbill` resend them. **Invoicing bills each line**,
  not one line per service. The form reuses the quote lines editor; an unnamed service is named after
  its lines' titles ("Corte + Massagem"). The API always returns `items` (an older row as its one line).

**Services detail (shipped 2026-09-29, `bfd0c55`):** a Serviços row opens a detail like an
invoice instead of the edit form, loaded from a new `GET /app/api/crm/services/{id}`. It shows the
client link, quantity × unit price, the booking it came from and the invoice that billed it, with
actions by status: Invoice opens the open-work form with only this row ticked, then Edit, Cancel,
Reopen and Delete. The booking detail gained Open service. Gotcha: `PATCH /crm/services/{id}` always
overwrites `notes`, so any status change must resend the whole row (`servicePatch` in `app.js`).

## Catalog picker in the line items editor (PR #43, shipped 2026-10-02, `48b2e38`)

Built by a Cursor cloud agent. The shared lines editor (Serviços, Orçamentos, Faturas, Pagamentos)
kept the catalog behind a `select` plus an Adicionar button in the table foot, so adding a catalog
item meant leaving the row being filled in. What matters later:

- **The description input is the picker.** A click or ↓ lists the whole catalog grouped under
  Serviço / Material with prices; typing filters by code, title, category and description, ignoring
  case and accents (`foldText`: `inspecao` finds "Inspeção técnica"). Picking fills description,
  quantity (1 unless already set), unit and price. A line typed by hand stays free text. The foot
  keeps one "Adicionar item" button, and pt-PT calls the section "Itens" (it said "Linhas"), like en.
- **Clipping gotcha:** `.lines` clips its overflow to round its corners, which cut the dropdown off.
  A `.lines` holding a combobox takes `.lines--combo` (no clipping, radii moved to head and foot); the
  backoffice channels table keeps clipping. The 560px stacking rule now targets the `.line__desc`
  wrapper instead of the bare input. Classes are in the repo's `design-system/components.md`.
- **Legacy `servico` items** fell out of both option groups and could not be picked at all; they now
  group with the services, matching `isServiceType()` on the Kotlin side.

CI tested and deployed it (deploy job green).

### On phones

- Since `b6448f9` (2026-10-01): the shared `.lines` editor had fixed columns (about 290px plus the description) and overflowed phone
  screens in quotes and invoices too; under 560px each line now stacks.

## Bookings are catalog services (shipped 2026-09-28, commits `3ab74db` + `3ecbe93`)

Bookings used to keep their own price-less `bookings.services` list, disconnected from the catalog,
clients and billing. The rework decided:

- **One service list.** A catalog row (`crm.standard_items`, type `service`) is bookable when it has
  `bookable = true` and `durationMinutes >= 5`. `/bookings/services` reads and writes those rows.
  `BookingCatalogMigration` runs at startup: each legacy `bookings.services` row goes into its
  tenant's catalog (merged onto a same-named service if one exists), appointments and Serviços rows
  are repointed, and the legacy row is stamped `catalogItemId`. Idempotent, deletes nothing.
  Catalog updates merge omitted `bookable`/`durationMinutes` from the stored row, because the
  backoffice form does not send them.
- **A booking snapshots** `catalogItemId`, `serviceName` and `priceCents`. Marking it `COMPLETED`
  creates or reopens one open Serviços row (`crm.client_services`) at that price, linked both ways.
  Moving away from `COMPLETED` cancels the row unless it is already invoiced. With the Clients module
  on, every booking gets a client, matched by phone (last 9 digits, formatting ignored) or created.
- **Rules.** `NO_SHOW` and `CANCELLED` free the slot. Reactivating re-checks overlaps. Customer
  sources (`WHATSAPP`/`INSTAGRAM`/`WEB`) must be in the future and inside opening hours; staff
  (`DASHBOARD`/`ADMIN`/`ASSISTANT`) can override. Check-then-write is serialized per tenant by an
  in-process mutex, which again assumes a single app instance. The pipeline passes the chat's
  customer to the tools (`BookingCallContext`), so WhatsApp bookings default to the sender's number.
  API errors are stable `{error}` codes, and the `app.js` `api()` helper now attaches `err.status`
  and `err.code` (the message stays `HTTP <n>`).
- **The `/app` calendar works in tenant-local day keys**, not the browser zone. The old grid bucketed
  bookings by browser-local hours, and a Home deep link to a booking outside the loaded week did
  nothing.

**Production rollout (2026-09-28).** A backup was taken right before the deploy
(`~/whatsapp-bot/backups/mongo-20260928T132459Z.archive.gz`). On first start the migration moved all
8 legacy booking services across 3 tenants. For one tenant, two were merged onto existing priced
catalog services; the other six became new catalog services at €0 in category "Marcações". All 20
appointments were repointed. Migrated appointments have no price snapshot, so follow-up `3ecbe93`
bills a booking without `priceCents` at the service's current catalog price (it had billed €0).
A catalog price can be per unit (`hora`, `m2`), but a completed booking bills 1 × that price, so an
hourly or per-m² service needs its Serviços quantity fixed before invoicing. Whether to derive the
quantity from the duration, or keep per-session catalog items, is still open.

Open gaps (reported, not fixed): bookings have no employee assignment or capacity (one booking at a time per tenant), no reminders, and no holiday closures. (The 20-row cap on `GET /app/api/crm/clients` was fixed by the client record work, see [crm-directories.md](crm-directories.md).)
