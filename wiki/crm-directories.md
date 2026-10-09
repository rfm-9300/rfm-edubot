---
updated: 2026-10-09
---

# Clients, suppliers and employees

The three directories: the record drawer, what each record holds, deleting versus archiving, a client's finances, and the client fields each company picks. Catalog items and Serviços rows are in [catalog-services-bookings.md](catalog-services-bookings.md); invoices and payments in [billing-and-pdfs.md](billing-and-pdfs.md).

## Client record (shipped 2026-09-29, commit `6899c2a`)

Opening a client in `/app` used to show a bare edit form (name, phone, address) plus plain text lists
of quote and invoice numbers. It is now a record drawer: profile card with contact actions, a money
strip, a needs-attention list, tabs for activity and each module, and a pinned create bar. Decisions
that matter later:

- **Assembled in the browser** from the existing per-client list endpoints (`?clientId=` on quotes,
  invoices, services and bookings) plus the conversations list, all in parallel. There is no summary
  endpoint. The WhatsApp chat is matched by phone on the last 9 digits, the same rule as
  `findByPhone`. A pending invoice past its due date counts as overdue, as on Home, because nothing
  flips invoices to `OVERDUE`.
- **New client fields**: `email`, `taxId` (NIF) and staff-only `notes`. `PATCH` keeps them when
  omitted and clears them on an empty string, while `address` keeps its old rule (null clears). The
  NIF prints on quotes and invoices on the client-number line ("CLT-005 · NIF …"), so the PDF client
  block never grows taller. `CrmTools` builds its own client JSON and must never return notes, email
  or NIF, because what it returns can reach a customer's chat.
- **Drawer return rule** in `app.js`: a drawer opened from the record gets `drawerReturn`. Its eyebrow
  becomes a back link, and a programmatic `closeDrawer()` after an action reopens the record (same
  tab, fresh data). ×, scrim and Escape call `closeDrawer({ dismissed: true })` and leave it. A
  generation counter stops the return when the caller opens another drawer right away. New drawer
  flows get this for free as long as they close with `closeDrawer()` after success.
- The booking form needs `state.bookingServices` and opening hours, which only the Bookings page
  loaded, so "New booking" from a client showed the services setup instead. The old client form had
  the same bug. The record now loads them (`ensureBookingData`).
- The client list endpoint returns up to 2000 rows when unfiltered, which fixes both the Clients page
  and the quote/invoice client pickers stopping at 20. `GET /app/api/crm/clients/by-phone` backs a
  duplicate-phone warning in the form. Correction: `crm.clients` (like suppliers and employees) has a
  unique `(tenantId, phone)` index, so an exact duplicate cannot be saved. It surfaced as a bare 500
  until the follow-up below mapped it to `409 phone_taken`. The warning is what catches the same
  number typed in another format.

**Follow-up the same day (shipped in `9efdbbb`):** the record pattern is generic. CSS moved from
`.client-*` to `.record`, `.record-card`, `.record-kpis`, and suppliers and employees open as records:
money strip (to pay with the overdue part, next due, paid), needs-attention, and a payments table.
New `GET /app/api/crm/suppliers/{id}` and `/employees/{id}` back them. The single `drawerReturn` became
a stack (`drawerTrail`, `openFrom(back, open)`, entries keyed like `client:ID`). The name in quote,
invoice, payment and booking details links to the client or payee record. Documents link to each
other (an invoice opens its quote; a converted quote shows and opens its invoice and hides Convert),
Back walks the stack one step at a time, and links that would lead back to the trail's top are
hidden. Converting a quote that already has an invoice now answers `409 already_invoiced`; before,
a double click could create two invoices. The quote detail finally has a PDF button.

## Deleting clients, suppliers and employees (shipped 2026-09-29, `8a0ec5a`)

None of the three directories could be deleted. Rodrigo's choice: delete a record when nothing refers
to it, archive it when it has history (hidden from lists and pickers, restorable), the same for all
three. Decisions that matter later:

- **History means** quotes, invoices, Serviços rows or bookings for a client, and payments for a
  supplier or employee (since `4f4b2cd` also an employee's registered or done services). A hard
  delete would orphan them: invoice PDFs are rebuilt on every download
  and 404 without their client, and invoices must be kept anyway. `DELETE /crm/{kind}/{id}` answers
  `409 in_use` then; archive and restore are separate `POST …/archive|restore` calls
  (`crm/DirectoryRecords.kt`, one shared helper for the three repositories).
- **Archived** (`archivedAt`) records drop out of the list endpoints (`?archived=1` lists only them),
  so out of every picker, the AI's `search_clients` and the Home totals. They stay reachable by id,
  so documents keep their name and the record drawer still opens from an invoice.
- **The phone stays taken.** `(tenantId, phone)` is unique and an archived record keeps its number.
  So a booking linked by phone, and the AI's `create_client`, restore an archived client instead of
  failing on the duplicate or creating a second one; the client form's duplicate-phone hint names
  the archived client. Suppliers and employees have no such hint, only the `phone_taken` toast
  ("check Archived too").
- **UI:** the record card has Edit plus Delete (Restore when archived) in its head. Delete confirms;
  when the record shows documents, or the server answers `in_use` because an off module hides them,
  the confirm offers Archive. An archived record shows a warning line and no create actions; the
  directory pages have Active / Archived chips.

## Client finances (shipped 2026-09-29, `2764ed1`)

Rodrigo asked for a Financeiro tab in the client drawer (what he earned from the client and what he
spent on them) and a client filter on the Financeiro page. Decisions that matter later:

- **Payments had no client**, only a supplier or an employee, so "spent" needed a link. A payment now
  has an optional `clientId`: set on create, changed or cleared with `PATCH /payments/{id}/client`
  (from the payment detail), listed with `GET /payments?clientId=`; index `(tenantId, clientId)`.
  Payments recorded before start unlinked, so a client's "spent" is 0 until someone links them; the
  tab says so. Linked payments also keep a client from being deleted (archive instead).
- **Client tab**, assembled in the browser like the rest of the record: received = paid invoices (plus
  what is still to collect), spent = paid linked payments (plus what is still to pay), net with the
  margin, and a movements table of invoices and linked payments that open their details. "Registar
  despesa" opens the payment form with the client preset; "Ver no Financeiro" opens the page filtered.
- **Financeiro page**: a client select (clients with paid movements); cards, week/month summaries and
  the ledger all use the filtered movements, and ledger rows now open the invoice or payment instead of
  jumping to their list page.
- **Gotcha found on the way:** `.form__row { display: flex }` beats the `hidden` attribute, so the
  payment form always showed both the supplier and employee fields (and both line sections), and the
  catalog form its booking-only fields. Fixed with `.form__row[hidden], .form__grid[hidden]`.

## Client and employee profiles, multi-line Serviços (shipped 2026-10-01, `b6448f9`)

Rodrigo asked for a profile on employees (birth date, address, NIF), city, postal code and contact
person on clients with NIF and Morada mandatory, and services that add up two or more catalog items.
Decisions that matter later (the first and the invoicing one were chosen without asking him):

- **"Mandatory" means a client saved by staff.** The `/app` form, `POST`/`PATCH /app/api/crm/clients`
  and the backoffice create answer `400 tax_id_required` / `address_required` (a `PATCH` that omits
  the NIF keeps the stored one). Bookings (`linkClient`) and the bot's `create_client` still create
  clients from a name and phone, because a customer booking on WhatsApp can't be asked for a NIF; such
  clients need both the next time someone edits them. The mobile app's create-client sends only name,
  phone and address, so it gets that 400 until it adds a NIF field (mobile isn't deployed; the open
  PR #44 adds it). Update: #44 merged with a NIF field on 2026-10-05, and since PR #52 (2026-10-06)
  NIF plus address is only the default, because each company picks its own required fields (see
  "Client fields per company").
- **New client fields** `postalCode`, `city`, `contactPerson` are optional and follow the email/NIF
  rule (kept when omitted, cleared on ""); `CrmTools` doesn't return them. **PDFs print street, postal
  code and city as one wrapped text** ("Rua do Castelo 3, 1100-129 Lisboa"). Gotcha: compact client
  blocks (68pt, e.g. the band design) fit only phone, reference and one address line, so a separate
  postal-code line was silently cut; the local tenant uses band, which is how it showed. Contact person
  is not printed.
- **Employee profile**: `birthDate` (stored as a `yyyy-MM-dd` string like the other CRM dates),
  `address`, `taxId`, kept when omitted and cleared on ""; a future or pre-1900 date answers
  `400 invalid_birth_date`. The record card shows the age. No bot tool reads employees.

(The same change let a Serviços row hold several lines and fixed euro-to-cents rounding: see [catalog-services-bookings.md](catalog-services-bookings.md) and [billing-and-pdfs.md](billing-and-pdfs.md).)

Verified locally: 303 tests green (12 new), and a headless-Chrome run through the client, employee,
service, invoice and PDF flows in light, dark, pt-PT and at 390px.

Production rollout (2026-10-01): backup `mongo-20261001T144104Z.archive.gz` (also in GCS) right
before; CI deployed `b6448f9`, Mongo not bounced, 0 `ERROR` lines. No data migration: existing clients
and employees get the new fields when next saved, and older Serviços rows read as one line.

## Client fields per company (PR #52, shipped 2026-10-06, `a1e6a74`)

A Cursor cloud agent's PR (branch `cursor/tenant-custom-client-fields-e2ed`, opened 2026-10-06): each
company chooses which standard client fields staff must fill and adds fields of its own, from a
**Fields** drawer on the Clients page. The intent is from the PR body; the rules below were checked
in the code when it merged.

- **Stored per company** as `directoryFields.clients` on its `Tenant` document (`Tenant.directoryFields`,
  keyed by `FieldDirectory` so suppliers and employees can use the same engine later; not done). Each
  company of a multi-company tenant is its own `Tenant`, so each has its own fields. A company without
  the key reads `ClientFields.DEFAULT`, NIF and address required, which is the `b6448f9` rule, so
  there was no migration and nothing changed for existing companies.
- **Standard fields:** name and phone are always required, because lists show the name and bookings
  and the bot find a client by phone. NIF, email, contact person, address, postal code and city can be
  required, and a missing one answers `400 tax_id_required`, `email_required`,
  `contact_person_required`, `address_required`, `postal_code_required` or `city_required`.
- **Custom fields:** at most 20, of type text, number, date, choice (at most 30 options) or yes/no,
  each optionally required and optionally a list column; labels are unique ignoring case. Values sit
  on the client as `customFields.<key>`, with keys generated as `cf_` plus 8 base-36 characters and
  checked by a regex because they end up in Mongo field paths. Renaming a field keeps its values.
  Errors: `custom_field_required` and `custom_field_invalid`, with the field key as `detail`.
- **Rules that look surprising:** a field's type never changes, and the server keeps the stored type
  and ignores the one sent instead of refusing. A yes/no field can't be required, because unticked
  is an answer. Removing a field hides it everywhere but keeps the stored values. A value sent back
  unchanged is kept even when it no longer fits, such as a removed choice. A save changes only the
  values it sends; null, blank or unticked clears one.
- **Who:** `GET /app/api/crm/clients/fields` answers anyone with the Clients module (an employee's own
  sign-in has none). `PUT` is for company admins and impersonating operators
  (`DashboardContext.isAdmin()`), others get `403 not_allowed`.
- **Staff saves only:** the `/app` client create and update and the backoffice API check the fields.
  Bookings, the bot's `create_client` and agents still create clients from a name and phone, as with
  the NIF rule before. Custom values are staff-only like notes: they are not in the employee portal
  (`PortalClientDto`), the bot's CRM tools or event payloads. The directory search matches text and
  choice values.
- **Not done:** suppliers and employees, PDFs, agent templates and mobile. The mobile client form
  still requires NIF and address on its own and can't fill the other fields, see
  [mobile](mobile.md).

Merging it: the only conflict with `17425b5` was the imports of `AdminRoutes.kt`. `main` was merged
into the branch twice (`930968b`, then `e9eacdc` once #51 had landed), 604 tests passed locally and in
CI, and headless Chrome showed the Details panel beside the installment-aware money strip on a client
record, the custom columns, search by a custom value and the Fields editor. Production rollout
(2026-10-06): backup `mongo-20261006T133934Z.archive.gz` (also in GCS) before merging; CI deployed
`a1e6a74`, Mongo was not bounced, 0 `ERROR` lines.

### Importing clients straight into a prod company (2026-10-07)

Rodrigo asked to load a PDF of a company's billing clients into production. How it was done, for next time:

- **No API path from a laptop.** The backoffice is Google-only in prod, so there is no token for
  impersonation. The import ran as a `mongosh --file` script in the Mongo container, after a backup,
  with a dry-run mode and a guard that aborts if the company's clients changed since the preview.
- **Mirror the repository exactly:** `CLT-nnn` from `crm.sequences` `client_number` (`$inc` with a
  `NumberLong`, the value is Int64), the field order and types of `ClientRepository` (nulls written,
  `automationPaused: false`), and one `domain_events` row per client (`client.created`/`client.updated`,
  payload `number`, `name`, `phone`, `email`, `hasEmail`; actor `OPERATOR`/`operator`; dispatch
  `PENDING`, 0 attempts). Record drawers read those events as activity, and the running app's
  dispatcher marked them `DONE` within seconds, which also shows it reads the rows.

- **Check the source against public registries first.** Validate every NIF's check digit and look each company up in the public registry: names in a customer's own documents can be misspelled or out of date, and an address may not be the registered seat. Rodrigo chose registered names plus the document's addresses with registry postal codes. Checking email domains for MX records catches typos, including ones staff had already saved.
- **Product gap: a client must have a unique phone.** The `(tenantId, phone)` index isn't partial and the phone prints on quotes and invoices, so a billing-only client without one gets an 8-digit placeholder (`99999991`, `99999992`, …) and a staff note saying so. Eight digits never match a real number, because `findByPhone` compares 9+ digit numbers on their last 9. An optional phone (partial unique index, per-company required field) is still open.
