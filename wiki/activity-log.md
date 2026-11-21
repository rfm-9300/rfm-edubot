---
updated: 2026-11-20
---

# Activity log: who did what

The company's record of who changed, cancelled or deleted what, and each record's History. It is the domain event
log the agents already read, not a second collection. The events Building appends are in
[building-projects.md](building-projects.md#events-for-the-agents-and-the-log); the agents' side of the same log is
in [agents-automations.md](agents-automations.md).

## One log: `domain_events`

- Every write in the CRM and Building appends an event after its own write, best effort, attributed by
  `currentActor()`: a dashboard user (`USER`, their id and email), the platform's support (`OPERATOR`), the bot
  (`BOT`, its channel), an agent run (`AGENT`), the system, a client from a link (`CLIENT`, the name they typed) or
  a supplier from theirs (`SUPPLIER`, "name · supplier"). Nothing new had to pass who acts: the sign-in validator
  fills the request's actor and the link routes set theirs with `ActorContext`.
- **The assistant acting for someone** is that user with `actor.via = "assistant"`: `DashboardAssistantService`
  wraps the assistant's tool pack (`AssistedToolPack`), so what its checked cards change reads "with the
  assistant".
- **History-only events.** A type without an `EventTypeSpec` is offered to no agent, so `DomainEventLog.append`
  writes it with `dispatch.status = "NONE"` and the dispatcher never claims it (`DomainEventTypes.dispatched`).
  The deletes, edits and cancellations added for the log (2026-11-20) are history only; giving one a spec later
  offers it to agents and dispatches it from then on. Until then the dispatcher claimed and closed every event,
  listened to or not.
- Payloads carry the record's number, name or title as they were, so a deleted invoice still reads "FT 2026/12".
  The API sends a `label` built from them and an allow-list of facts (`to`, `from`, `via`, `decidedBy`, amounts,
  `action`, `phase`…); phones, emails and ids in payloads stay on the server.
- What customers send in (`message.received`, `email.received`, `instagram.comment.received`) isn't activity and
  is left out.
- Kept 365 days (the collection's TTL). Indexes for the log: `(tenantId, occurredAt, _id)` for the company's pages
  and `(tenantId, actor.id, occurredAt)` for one person.

## What it covers

CRM: clients (created, edited, archived, restored, deleted), quotes (created, edited, status, deleted), invoices
(created, paid, installments set and paid, ATCUD code, cancelled, deleted), Serviços rows (created, edited,
invoiced, deleted), payments (created, linked to a client or project, paid, cancelled, deleted), suppliers and
employees (created, edited, archived, restored, deleted), catalog items (created, edited, deleted), bookings.
Building: everything [building-projects.md](building-projects.md#events-for-the-agents-and-the-log) lists. Not yet:
settings, the persona, chat flows, agents' own definitions, tags and catalog categories.

A new write that someone may later ask "who did this?" about appends an event in the repository or service that
writes, like the others.

## API and who sees it

`ActivityRoutes` (`dashboard/ActivityRoutes.kt`), behind `overview`, which every company has:

| Route | Who | What |
|---|---|---|
| `GET /app/api/activity?actor=&area=&from=&to=&before=&limit=` | admins (`isAdmin`), else 403 `not_allowed` | the company's log, newest first; `actor` is `user:<id>` or an actor type, `area` a module (`invalid_area`), dates in the company's zone, `before` the last row's id |
| `GET /app/api/activity/people` | admins | the team's sign-ins, for the person filter |
| `GET /app/api/activity/records/{type}/{id}` | anyone whose company has that record's module | the record's events and those naming it: a client's shows its invoices, a project's its changes and measurements |

Members read a record's History but not the whole log: watching colleagues is the owner's call, and every member
can open the records anyway.

## The screens

- **Activity** (`app/activity.js`): a page under Settings in the nav, for admins only (not a module: `app.js`
  adds it to an admin's pages). A table When / Who / What / Record with person, area and date filters in a
  `.panel__filters` row and "Load more". A row opens its record unless it was deleted.
- **History** on a record: a tab on the client and the project, a panel above the change order's and the
  measurement's actions, and under a quote, invoice and payment (`mountRecordHistory`). A row about another record
  (a project's change) opens it. An issue keeps only its own status history, which already names who moved it and,
  from the supplier's link, from where: a second "History" panel there read as a duplicate.
- **What and Record are two columns**, not one sentence: "Deleted" · "Invoice FT 2026/12". Building "Apagou a
  fatura" from pieces breaks on Portuguese and Spanish genders and contractions ("de a" → "da"), so the verbs
  (`app.activity.verbs`) take no object and the nouns (`app.activity.nouns`) no article. A status change reads
  "Moved to “Accepted”" through `recordStatusLabel`.
