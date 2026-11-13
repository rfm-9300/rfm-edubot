---
updated: 2026-11-13
---

# Record tags

Free-text tags a company's staff put on its records, to group them its own way. Shipped 2026-11-13
for clients, suppliers, employees, Serviços rows, catalog items, quotes, invoices, payments and
Building projects. Rodrigo chose this over per-company custom fields on every record type, which
[crm-directories.md](crm-directories.md) describes for clients only: typed fields cost a model, a draft, a
validation, repository paths and a form per record type, and every surface that ignored them (PDFs,
the bot, agents, the portal, mobile) became a gap.

## How it works

- **Stored beside the record, not on it.** One document per tagged record in `record_tags`
  (data database): `{tenantId, kind, recordId, tags}`, unique on `(tenantId, kind, recordId)`
  (`RecordTagRepository`, indexed in `MongoModule.initialize()`). No record model, draft, DTO or
  repository changed, so a record rewritten by the bot, a booking, an agent or an import can't lose
  its tags, and `restore-tenant.sh` restores them with the company's other documents.
- **`TagKind`** names each taggable record, its collection and the field holding its id. A catalog
  item is keyed by its own string `id`, the others by `_id`. A new kind is one enum entry plus one
  `recordTagRoutes` line in the module that owns the record (the audit test lists them).
- **Two routes per kind, mounted inside the owning module's gate** (`dashboard/RecordTagRoutes.kt`,
  so `requireModules` covers them and `DashboardRouteAuditTest` stays green): `GET {path}/tags` answers
  every tagged record as `id → tags`; `PUT {path}/{id}/tags` replaces one record's tags, an empty list
  removes them. A literal `tags` segment beats the record's `{id}` route in Ktor.
- **Rules** (`RecordTags.clean`, the same as the backoffice board's labels): trimmed, inner spaces
  collapsed, blanks dropped, repeats dropped ignoring case (first spelling stays), at most 10 tags of
  30 characters. Past the limits it answers `400 too_many_tags` or `tag_too_long` instead of cutting.
  `404 record_not_found` for an id that isn't a record of that company, so tags can't pile up for
  ids that were never records.
- **No cleanup hooks.** `forKind` checks that each tagged record still exists and deletes the tag
  documents of those that don't, so deleting a client, an invoice or a project needs no extra code.
- **Staff only.** Tags are not in any DTO, PDF, event payload, the bot's tools, the assistant's tools
  or the employee portal, like a client's notes.

## The front end

One script, `app/tags.js` (`state.tags[kind]` holds each list's `id → tags`, `state.tagFilter[kind]`
its filter):

- **Each list page loads its tags beside its records** (`loadModule` starts `loadTags(tab)` first,
  because a list's tab id is its tag kind) and gets three things: a **Tags column**, a compact
  **filter** select in the panel tools and **search** that matches tag text.
- **Columns:** the three directories have a column picker, so `tagsColumn(kind)` is on by default once
  the company has tagged something in that list. The other lists always show every column, so they
  spread `tagsColumnIfUsed(kind)`, which is empty until something is tagged. A company that never
  tags never sees an empty column.
- **Filter:** the choice is `t:<tag>`, or `none` for untagged records; a tag no record has any more
  counts as unset. A filter that narrows a list also narrows the chip counts beside it (the design
  system's rule that a count equals the rows it leads to), except where those counts never followed
  the other filters (Projects' figures read every project).
- **The strip:** `openDrawer(title, body, wide, { tags: { kind, id } })` puts a `tagStrip` above the
  body, so every record drawer has the same editor and it survives the body redrawing itself. It
  saves each change (the whole list is sent), puts the list back if a save fails, and redraws the page
  behind so the column and filter follow. Escape in its field only ends the field, not the drawer.
  Suggestions come from every tag already loaded, not just that kind's.
- A tag's pill is `.pill.pill--tag` (neutral, no status dot, mixed case); `.tags` wraps them in a
  cell and `.tags-strip` is the drawer's row ([components.md](../design-system/components.md)).
- Strings are `app.tags.*` in the three catalogs, with the server's error codes under `errors`.

## Not done

- Bulk tagging from a list's row selection (the selection bar already takes page actions).
- Tags on bookings, Building's diary, issues, change orders and measurements, conversations and
  contacts.
- Tags on PDFs, in the bot's and the assistant's tools, in agent triggers and in mobile.
- Managing the vocabulary (renaming or merging a tag everywhere): a tag exists only as long as a
  record has it.
