---
updated: 2026-10-09
---

# WhatsApp Embedded Signup and Meta's rules

Connecting a company's WhatsApp number through Embedded Signup, and what Meta's platform demands before messages flow: webhook fields, display names, payment methods, App Review and template permissions. Which numbers, portfolios and accounts are involved is kept out of this public repo.

## WhatsApp Embedded Signup: the register step (shipped 2026-09-29, `b48bf16` + `0a02874`)

`POST /app/api/whatsapp/connect` (and its backoffice twin) receives the Meta popup's `code`, `wabaId`
and `phoneNumberId`; `WhatsAppSignupClient` exchanges the code for a business token, then calls Graph.
Rodrigo's first production connects failed twice on 2026-09-29, both at `/register`. The third one
worked (on a WhatsApp-provided +1 555 number, not a test number; see the display-name rule below):
the lookup found the number already on Cloud API, because
the second attempt's timed-out register had finished at Meta, and skipped register.

- **Look the number up before registering.** The client reads `platform_type` and `is_pin_enabled`
  first. `CLOUD_API` means already registered, so `/register` is skipped. A PIN on an unregistered
  number is replaced with `POST /{phone-number-id}` `{"pin": …}` (Meta's documented path when the PIN
  is unknown), and register then sends that same PIN. Never retry register with a guessed PIN: a wrong
  one is error `133005`, and repeats lock the number (`133008`, `133009`).
- **Register is slow.** It ran past the shared Graph client's 15 s timeout (`whatsappHttpClient` in
  `Application.kt`); it now gets 60 s, and a Graph timeout answers `504 graph_timeout` instead of an
  unhandled 500. A register that times out on our side can still finish at Meta, which is why the
  lookup comes first.
- The PIN sent to register is random and not stored. Getting control back means the API reset above
  or WhatsApp Manager, where turning two-step off needs an email link to the portfolio owner.
- Failures log `WhatsApp Embedded Signup failed: … reason=… graphCode=… graphMessage=…`. The page
  shows only the HTTP status, because the `/app` toast ignores the error code the server returns.
- The first failure was the `encodeDefaults` gotcha ([gotchas.md](gotchas.md)): the register
  body lost `messaging_product`.

**Inbound never reached prod (found and fixed 2026-09-30).** After that first connect, a message
to the number never showed in the inbox. No request hit `/webhook`, `webhook_events` (7-day TTL on
`receivedAt`) was empty, and prod had no WhatsApp conversation at all. The Meta app had its
callback URL (`/webhook`, active) but no webhook fields subscribed. Everything else checked
out: the signup flow's `subscribed_apps` call had put the app on the WABA, there was no WABA or
phone-number override, and the public `/webhook` answers from the bot. Rodrigo subscribed `messages`
in the App Dashboard the same day (field version v26.0); the next message was stored within a
second.

- **WhatsApp webhook fields can only be set in the App Dashboard** (WhatsApp → Configuration →
  Webhook fields → `messages` → Subscribe). `POST /{app-id}/subscriptions` does not support
  WhatsApp. Meta does not replay messages sent before the field was on.
- **Checking without the dashboard**, from the VPS: `GET /{app-id}/subscriptions` with the app token
  (`{app-id}|{secret}`) lists the subscribed `fields` (no `fields` key means none);
  `GET /{phone-number-id}?fields=webhook_configuration` shows where that number's webhooks go;
  `GET /{waba-id}/subscribed_apps` lists the apps on the WABA. The business token is on the tenant's
  binding in Mongo. In a script fed through `ssh … 'bash -s'`, give `docker compose exec -T`
  `</dev/null`, or it swallows the rest of the script.

- **Platform settings beat `.env`.** Webhook signature checks and the signup's code exchange use `WA_APP_SECRET`, and a non-blank override in the backoffice's platform settings wins over the `.env` value ([ops-and-deploy.md](ops-and-deploy.md)). Check which one is live before rotating or clearing either.
- **A WhatsApp-provided 555 number can't send until its display name is approved.** Every send fails with `131037` ("WhatsApp provided number needs display name approval"). These are the free +1 555 numbers Meta offers during Embedded Signup: they register and receive webhooks, but every send is refused until the name passes review. `name_status: AVAILABLE_WITHOUT_REVIEW` does not count, and neither the WABA's `account_review_status: APPROVED` nor the number's `CONNECTED` status is the display name. Check it with `GET /{phone-number-id}?fields=name_status,new_name_status`: WhatsApp Manager shows no review status at all (Phone numbers only has a **Name** column), so the API is the only place to see it. A review starts when the name is edited (Profile tab → Display name → Edit, or `POST /{phone-number-id}?new_display_name=`), and then `new_name_status` goes `PENDING_REVIEW`. WhatsApp Manager's Edit flow won't continue with the unchanged name, so a review needs a new one: an unverified business's display name must match its portfolio name, Meta's guidelines (as providers quote them) accept a test or demo name tied to the business, and a URL-like name risks rejection. After approval the number must be registered again, and `/register` needs the two-step PIN, which we don't store, so set a new one first; Meta's docs say registering before approval has no effect. The API route answered `(#200) Permissions error` with the Embedded Signup token, likely because such tokens hold only the `MESSAGING` task on the WABA (see template permissions below), so a portfolio admin submits the name in WhatsApp Manager. The other way out is connecting a number the business owns, which can send without approval.
- **Waiting alone probably won't get a 555 name approved.** Meta's display-names doc says verification runs "when you reach a higher messaging limit", and an unverified portfolio only leaves the 250 limit (`whatsapp_business_manager_messaging_limit`; `messaging_limit_tier` is deprecated) by business verification or 2,000 delivered template sends, which a number that can't send never reaches. So the realistic ways to unblock replies are Business Verification of the portfolio (it also clears `141010`) or a number the business owns. Inferred from Meta's docs and the API state in late September and early October 2026, not confirmed by an outcome.
- **`GET /{phone-number-id}?fields=health_status`** (also on the WABA) gives Meta's own `can_send_message` verdict per entity (phone, WABA, business, app) with error codes and fixes, such as `141010` for an unverified business and `141006` for a WABA with no valid payment method. Meta says `141006` blocks business-initiated (template) messages, and its Embedded Signup docs say a Tech Provider's customers must add a payment method to the WABA before messaging at all, so template sends fail (probably `131042`, shown as "account restricted") until a card is added in WhatsApp Manager. SIP errors in the same response (`138024`, `138025`) are about calling and irrelevant.

- **Since 2026-10-01 the card also gates replies** (Meta's pricing pages, checked 2026-10-05).
  Service messages, the free-form replies inside the 24-hour window and so every AI and inbox
  reply, are charged per delivered message from that date, with 1,000 free per business number
  per month. Meta's notice says it stops delivering service messages for a WABA with no payment
  method on file; a later pricing-page edit, as providers quote it, still delivers the free 1,000
  first. Either way every tenant's WABA needs a card before the bot can be relied on, and `141006`
  is no longer template-only. Inferred from the docs, not yet seen on our account. Where:
  `business.facebook.com/latest/billing_hub/payment_methods` (Billing & payments → Payment
  methods) with the WABA's portfolio selected → **WhatsApp Business accounts** tab → pick the WABA
  → **Add payment method**, or WhatsApp Manager → the WABA → Settings → Payment settings. Visa or
  Mastercard only (prepaid and virtual cards are refused), then make it Default. The card goes on
  the WABA, not the number, and needs full control of the portfolio (or finance access) plus
  Manage WhatsApp account. It does not touch the 555 number's `131037`.
- **A failed AI reply showed as delivered** (fixed and shipped 2026-09-30, `5e969c7`).
  `MessagePipeline` inserts the assistant row as `DELIVERED` before `responder.sendText` (also the
  budget and PDF replies), and the catch block only marked the webhook event `failed`, so the inbox
  showed a reply the customer never got. Now `sendReply` calls `MessageRepository.markSendFailed`
  (`FAILED`, `statusAt`, Meta's code and text, same policy as `InboxService.send`) and rethrows, so
  the event is still marked failed. The inbox's failed bubble and error line already worked for any
  author; `inboxTick` now shows the not-delivered icon before its `tracked` check, and `131037` maps
  to `inboxErr_display_name_unapproved`. Rows stored before the fix keep `DELIVERED`. A failed reply
  still goes into the AI's context and can't be retried from the inbox (Retry is agent-only).
- **Meta's WhatsApp Business Tools MCP** (`https://mcp.facebook.com/whatsapp_business_tools`, beta,
  can configure webhooks) can't be used from the Cursor CLI: its dynamic client registration answers
  "Dynamic registration is not available for this client", for Cursor and for `mcp-remote` alike.
  Meta documents only Claude, Codex and ChatGPT. The entry stays in `~/.cursor/mcp.json` in case
  Meta opens it up.

## App Review and template permissions

- **App Review.** `whatsapp_business_messaging` wants a recording of the app sending and the WhatsApp client receiving; a Meta test number only messages recipients on its App Dashboard list (otherwise `131030`, shown as "recipient not allowed"). `whatsapp_business_management` wants template creation or management inside the app: Settings → WhatsApp templates covers that (added 2026-09-30). Meta's Tech Provider guide (checked 2026-10-05) adds three rules: submission opens only after Business Verification; each permission gets its own video and written use case, never one video for both; and Meta accepts stand-ins, a recording of the App Dashboard's API Setup cURL sending to a test recipient instead of the app, and WhatsApp Manager creating a template instead of the app. The app's test number (API Setup) needs no payment method, per Meta; send once from API Setup before recording. It can be bound by hand to a separate demo tenant in the backoffice tenant form, but that form takes only a phone number ID and token, not a `wabaId`, so the demo tenant's templates page answers `409 no_waba`; a template video needs an Embedded Signup tenant, whose token can list its WABA's templates. A business-owned number answered inbound customer messages on 2026-10-07 although `health_status` showed its WABA `BLOCKED` by `141006` and its business not verified (`141010`); templates and other business-initiated sends don't go through in that state.
- **Embedded Signup tokens can't create templates.** Creating a template from the app failed with `(#100) Need permission on either WhatsApp Business Account or owner/shared business` (the dashboard only says "Meta didn't accept the template"). The token was fine (a `SYSTEM_USER` of our Meta app holding `whatsapp_business_management` on the WABA), but `GET /{waba-id}/assigned_users?business={business-id}` showed that system user with only the `MESSAGING` task: enough to send and to list templates, not to create them. A portfolio admin has to give it `MANAGE` (or Manage templates) on the WABA, and the usual ways don't work: Business settings' Assign people doesn't list Embedded Signup system users, and `POST /{waba-id}/assigned_users` needs `business_management`, which our app doesn't offer. What worked for one tenant (2026-10-07): the tenant's portfolio shared the WABA, as a partner with full control, with the portfolio that owns our Meta app; an admin system user there got the app and the WABA, and its never-expiring token (`whatsapp_business_management` + `whatsapp_business_messaging`) replaced the tenant's token in the backoffice tenant form, which keeps `wabaId`, display name and `source`. Template creation then worked. Every other Embedded Signup tenant still gets `MESSAGING` only.
