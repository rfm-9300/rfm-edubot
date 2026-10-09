---
updated: 2026-10-09
---

# Conversations inbox and channels

Where customer chats arrive and how the team answers them: the Conversations inbox, WhatsApp templates and media, the website widget and Instagram. Connecting WhatsApp, and Meta's platform rules, are in [whatsapp-and-meta.md](whatsapp-and-meta.md).

## Conversations inbox: agent replies, delivery ticks, templates, media (shipped 2026-09-30, `ac4fef7`)

Rodrigo asked for better conversation management plus a way to send from the dashboard for Meta App
Review. `InboxService` (`dashboard/`) holds the rules; `docs/architecture.md` → "Conversations inbox"
has the sequence diagram. Decisions and gotchas:

- **An agent reply pauses the AI** for that conversation (`autoReplyPausedBy`/`At`), text or template,
  so the bot never answers over a person; the thread shows who paused it and a Resume button. Chosen as
  the default without asking Rodrigo; revisit if tenants want the AI to keep going.
- **24-hour window is enforced before calling Meta** (`409 window_closed`). Reason: Meta usually
  *accepts* an out-of-window text and fails it later by status webhook (`131047`), so the send call
  alone would claim success. The pipeline stores `lastInboundAt` + `unreadCount` on new customer
  messages only (replays don't count); older conversations fall back to their newest customer message.
- **Delivery ticks need the wamid.** Sends keep `waMessageId` and start `SENT`; status webhooks move it
  forward only (`FAILED` sticks) because they arrive late, twice or out of order. AI replies keep no id
  and show no ticks (their send path stores the row before sending).
- **Templates** list from `GET /{wabaId}/message_templates` (needs the binding's `wabaId` from Embedded
  Signup; `409 no_waba` otherwise) and are marked unsendable when they need a media header, dynamic
  button or one-time code. A new conversation sends first and then creates the contact with Meta's
  resolved `contacts[0].wa_id`, so the reply lands in the same conversation.
- **Live updates by polling** `GET /conversations/{id}/updates?since=` (4 s thread, 15 s list, paused on
  hidden tabs); the cursor is server time before the query minus 5 s and the client merges by id.
- New response DTO fields have no defaults on purpose (the [`encodeDefaults` gotcha](gotchas.md) would drop `0`/`true`).
  `threadByConversation` used to return the *oldest* 200 messages; it now returns the newest.

- **Template management** lives in Settings → WhatsApp templates (list with review status and
  rejection reason, create with a live preview, delete). `TemplateDraft.problem()` mirrors Meta's rules
  (positional `{{n}}` in order, not at the start or end, one example each, header/footer ≤ 60, ≤ 3 quick
  replies) so bad drafts fail before Meta; the send picker only offers approved templates.
- **Customer media and replies (2026-09-30).** The webhook used to drop everything but text, including
  quick-reply taps (`type: button`) on the templates the dashboard now creates. Button/list replies,
  locations (with a map link) and contacts now reach the AI as text; photos, voice notes, videos,
  documents and stickers are stored for the inbox without an AI reply and marked in the AI context.
  The dashboard shows media from blob URLs fetched with the bearer token; only raster images, audio and
  video render inline, everything else downloads, so a customer file can't run in the app's origin.
- **Class prefix gotcha:** `tpl-` belongs to the PDF document studio (`doc-template.js`, `.tpl-table`
  keeps paper-white rows in dark mode). The WhatsApp template UI uses `wa-` after a `.tpl-table` clash
  made its table unreadable in dark mode.

- **Website chats are read-only in the inbox.** `WEB` conversations show there, but the backend refuses operator sends on them (ephemeral WebSocket session, no send-later API), so the composer is replaced with a note. History in the 2026-09-11 pass on [ai-persona-and-bot.md](ai-persona-and-bot.md).

## Website widget

The website chat is an isolated `tbl-` prefixed embed served from `resources/widget/`; it does
not load dashboard CSS into the host page. Tenant dashboard customization is encoded in the
generated script's `data-*` attributes (title/subtitle, welcome and composer copy, launcher,
accent, left/right position, light/dark/device theme). The only server-persisted widget setting
is the allowed-origin list; changing embed appearance requires copying the updated snippet.
The widget defers its WebSocket connection until first open, reuses a browser session ID, and
becomes a full-screen dialog below 480px.

Instagram DMs are a messaging channel (same Conversations inbox as WhatsApp). The optional
dashboard module `instagram` is a separate comments inbox for that account's public posts:
webhook-stored comments, Graph list/reply, reconnect required after adding
`instagram_business_manage_comments`. Do not route post comments through `MessagePipeline`.
