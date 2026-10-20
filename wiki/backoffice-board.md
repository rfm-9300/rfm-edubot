---
updated: 2026-10-20
---

# Backoffice board

The platform team's work board in the backoffice (Board in its menu): Trello-style boards with
columns of cards, Jira-style card keys, checklists, comments, and cards linked to a tenant. Built
2026-10-20 at Rodrigo's request, "so we can follow our work". The password vault beside it has its own
page: [password-vault.md](password-vault.md).

## What it is

- **Boards, one per project.** A board has a name, an optional description, a key and its columns.
  Every backoffice admin sees and changes every board; there are no per-board members.
- **The key is fixed.** It is 2 to 6 letters or digits starting with a letter (`OPS`), unique, and
  numbers the board's cards in creation order (`OPS-12`), so a card can be named in chat or a commit.
  It can't change after creation, since that would rename every card. `board.boards.lastNumber` is
  taken with an atomic `$inc`, so two cards never share a number.
- **Columns** are the board's ordered list (`columns: [{ id, name, done }]`), replaced in one call
  (`PUT …/columns`) that renames, reorders, adds and removes together. A column that still holds a
  card that isn't archived can't go (409 `column_not_empty`, naming it); its archived cards move to the
  first column left, so restoring one finds a column. A `done` column's cards no longer show their due
  date as late. New boards get To do, In progress and Done (done) in the creating admin's language:
  the browser sends the names, so the server stays language-free.
- **Cards**: title, description, assignee (an admin's email), due date, priority (Low, Medium, High,
  Urgent; Medium shows no pill), labels (free text, up to 10, repeats dropped regardless of case),
  a checklist, comments, and an optional tenant. Archiving hides a card from the board; it waits under
  Archived cards to be restored. Deleting removes it and its comments.
- **Who did what** is the signed-in admin's email (`createdBy`, `updatedBy`, a comment's `author`),
  from the Google session's `email` claim. A password sign-in carries no email; the UI then says
  "an admin (password sign-in)". Only a comment's author deletes it.
- **Tenant link.** A card may name the tenant it is for. The board filters by tenant, and the tenant's
  record in the backoffice lists its cards that aren't archived, on any board, with New card to add
  one for that tenant (`GET /admin/api/board/cards?tenantId=`).

## How it is built

- `admin/board/`: `BoardModels.kt`, `BoardRepositories.kt` (`board.boards`, `board.cards`,
  `board.comments`, in the platform database), `BoardService.kt` (rules, returns domain types and
  `BoardRefusal`), `BoardRoutes.kt` (`/admin/api/board`, behind `admin-jwt`; refusals become the
  `http/` error contract). Mounted in `AppRoutes.kt` with `BoardService.of(mongo, tenants)`.
- **Order inside a column** is a `position` double. A move puts the card halfway between its new
  neighbours, so a drag writes one card; once two neighbours are less than 1e-6 apart the column is
  respaced (1024, 2048…). `POST /cards/{id}/move` takes `{ columnId, index }`, the index among the
  column's other cards that aren't archived; an index past the end means last.
- **A card's PATCH names only what changed**, and a nullable field sent as `null` clears it. The drawer
  diffs its fields against the card it loaded, so two admins editing different fields of one card
  don't undo each other. The checklist is replaced whole on each change.
- The UI is `backoffice/board.js` (the view, filters, quick add, drag) and `backoffice/board-card.js`
  (the drawers and the tenant record's panel); classes in
  [`design-system/components.md`](../design-system/components.md#backoffice-board).

## Decisions

- **Dragging follows `/app`'s column drag** (`app/columns.js`): a mouse drags at once, a finger or pen
  holds the card 350 ms first, so a swipe over the cards still scrolls the page; once a drag starts a
  non-passive `touchmove` listener stops the scroll. The card follows the pointer (`position: fixed`,
  geometry set inline), a dashed slot shows where it lands, the board and page scroll near the edges,
  Escape cancels, and the click that ends a drag doesn't open the card. The page moves the card at
  once and reloads the board if the server refuses.
- **Where a card lands is read from its neighbours**, not from the slot's position among the cards on
  screen: with a filter on, the visible cards are a subset of the column, so the index sent is the
  position of the card it was dropped before (or after) in the full column.
- **Keyboard:** Enter opens a focused card; Alt with ↑ ↓ moves it within its column and with ← → to the
  neighbouring column at the same height. The card keeps the focus. The drawer's Column select is the
  other way to move a card without dragging.
- **No sideways scroll on phones:** columns sit side by side and scroll inside the board's panel on
  wide screens, and stack one under another under 700px.
- **Cards added while filtered take the filter** (the tenant, the assignee), so a quick-added card
  doesn't vanish from the board it was added to.
- **Other admins' changes** show when the board reloads: every minute while the Board view is open and
  as soon as the tab is visible again, skipped while someone drags, types in the board or has a drawer
  open. There is no live push.

## Not done

- No per-board permissions, watchers, notifications, due-date reminders, activity history, sprints,
  card types or attachments. Rodrigo chose comments, checklists and the tenant link for the first
  version; activity history was offered and left out.
- A card's description is plain text (line breaks kept), not Markdown.
