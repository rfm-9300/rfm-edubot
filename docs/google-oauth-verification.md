# Google OAuth verification - Gmail integration

## What we ask Google for

A company admin connects the company's own Gmail or Google Workspace account in the tenant
dashboard (**Settings > Channels > Email (Google) > Connect Gmail**), so quotes, invoices and
automation emails go out from the company's own address. It is a user-authorized OAuth flow (web
application client, authorization code, `access_type=offline`), one grant per connected account.

| Scope | Class | Requested | Why |
| --- | --- | --- | --- |
| `openid`, `email` | non-sensitive | at connect | Identify the connected account and show its address in Settings. |
| `https://www.googleapis.com/auth/gmail.send` | **sensitive** | at connect | Send the emails a person or an automation of the company writes. |
| `https://www.googleapis.com/auth/gmail.readonly` | **restricted** | only with "Use my inbox in automations" (or "Read this inbox" on the Email page) | Read new inbox messages to link them to clients, show them to the company's team on the Email page with the dashboard actions they call for, and start the company's email automations. |
| `https://www.googleapis.com/auth/gmail.modify` | **restricted** | same, together with `gmail.readonly` | Planned: mark the inbox messages an automation handled. Not used yet (see below). |

Sending needs brand verification plus sensitive-scope verification. Inbox reading needs
restricted-scope verification and a yearly **CASA** security assessment; it stays off (the scopes
are never requested) until that is done. `gmail.readonly` + `gmail.modify` are asked for with
incremental consent, so companies that only send never see them.

## Google Cloud project

1. Use the Firebase project **thebotslab** (one brand on the consent screen for sign-in and Gmail).
2. **APIs & Services > Library**: enable the **Gmail API**.
3. **OAuth consent screen** (Google Auth Platform > Branding):
   - App name `TheBotsLab`, support email `hello@thebotslab.pt`, logo (120x120, the brand mark).
   - Home page `https://thebotslab.pt`, privacy policy `https://thebotslab.pt/privacy`.
   - Authorized domain: `thebotslab.pt`.
   - Developer contact: `hello@thebotslab.pt`.
4. **Data access**: add `openid`, `email` and `gmail.send` (later `gmail.readonly` and `gmail.modify`).
5. **Clients > Create client > Web application**, named `Gmail integration` (separate from the
   Firebase sign-in client). Authorized redirect URI: `https://thebotslab.pt/integrations/google/callback`.
6. Put the client in the app: backoffice **Platform settings > Google** (`GOOGLE_OAUTH_CLIENT_ID`,
   `GOOGLE_OAUTH_CLIENT_SECRET`, `GOOGLE_OAUTH_REDIRECT`) or the same names as environment variables,
   plus `INTEGRATIONS_ENCRYPTION_KEY` (env only: `openssl rand -base64 32`). Without the key the
   Gmail row stays hidden and connecting answers 503.

## Testing mode (before verification)

- At most 100 test users, listed under **Audience > Test users**. Only those Google accounts can
  consent.
- Refresh tokens expire after 7 days: connected accounts drop to "Needs reconnecting" every week.
- Don't onboard real companies until verification passes and the app is **In production**.

## Exact use of each scope (for the verification form)

**`gmail.send`**: TheBotsLab is a CRM for small service businesses. A company admin connects the
company's Gmail account so the business can send its quotes and invoices (as PDF attachments) and
the messages of automations it sets up (for example, a reminder before an invoice is due) from its
own address. We send only emails that a signed-in user of that company sends from the dashboard
(the "Send by email" button on a quote or invoice, the "Send a test email" button in Settings) or
that an automation the company created and turned on sends. Each sent email is shown on the
client's record. We never read the mailbox with this scope.

**`gmail.readonly` / `gmail.modify`** (Phase 4): when a company admin turns on "Use my inbox in
automations", we read the messages that arrive in its inbox from then on (not its own sent mail,
spam, or promotions from strangers), link them to the company's clients by sender address, show
them on the client's record, and run the email automations the company chose (for example, logging
a supplier's bill or replying to a new lead in its thread; replies go out with `gmail.send`).
With the Email page (an opt-in dashboard module), the company's team also reads those messages
there and answers them in their thread; when someone opens a received email, its text goes to the
AI model provider to summarize it and suggest dashboard actions (add the sender as a client, prepare
a quote, register a supplier's bill…), which only fill forms a person reviews and saves.
We keep a message's text for 90 days, and what the model read with it, and never keep attachment
contents. Nothing in the mailbox is changed or deleted.

`gmail.modify` is requested with `gmail.readonly`, as the plan pairs them, but no feature uses it
yet. Before the restricted-scope submission, either give it its use (labelling the messages an
automation handled) or drop it from `GoogleScopes.inbox`: reviewers refuse scopes the app doesn't use.

## Limited Use: where the app keeps its promises

| Requirement | How |
| --- | --- |
| Disclose the use and link the policy | `/privacy` section 5 carries Google's Limited Use sentence and the link (`LegalRoutesTest`). |
| Use only for user-facing features | Sends come from dashboard buttons (Send by email, the Email page's reply) or the company's own automations (`EmailService`, `email.send`). The Email page's AI reading runs when someone opens an email there, and its result is shown on that page only (`EmailInsightsService`). |
| Tokens protected | AES-256-GCM at rest (`TokenCipher`), never sent to the browser (`IntegrationConnectionDto`). |
| Keep no more than needed | Email text dropped 90 days after its date (`EmailRetention`), with what the Email page's model read in it and what automation runs and approvals made of it; runs and events never store the text itself; attachment contents never stored. Tasks and notifications an automation made from an email keep what its steps wrote into them (an AI summary, say), and neither the retention job nor a disconnect clears them: notifications expire after 90 days, tasks stay. |
| Delete on request | Disconnect deletes tokens and kept mail, redacts the account's emails from the activity log, runs and approvals, and revokes the grant (`DELETE /app/api/integrations/{id}`, `EmailService.forget`); `/data-deletion` explains it. |
| No ads, no model training | Stated on `/privacy`; Gmail text reaches the LLM provider only inside a step the company set up, or when someone opens a received email on the Email page. |
| No human reading | Stated on `/privacy`; support access only when the company asks. |
| Least privilege | Send-only at connect; restricted scopes only after the company opts in. |

## Recording the demo video

1. Use a test Google account that is on the test-user list, and a company with a synthetic client
   whose email you control. English UI (**Settings > Language**), English browser.
2. Show the browser address bar throughout: the consent screen URL must show the OAuth client ID.
3. In **Settings > Channels**, press **Connect Gmail**. On Google's screen, show the app name, the
   requested permissions ("Send email on your behalf") and press **Continue**.
4. Back in Settings, show the connected account, open **Manage**, set a sender name and signature,
   press **Send a test email**, and show it arriving in the test inbox.
5. Open a quote, press **Send by email**, show the draft (recipient, subject, text, PDF attached),
   send it, then show the email in the recipient's inbox and on the client's **Emails** tab.
6. Open **Agents**, show an automation with an **Email** step (for example the invoice reminder) and
   the email it sent.
7. Back in Settings, **Disconnect** the account and confirm; show that the row is gone and the
   client's Emails tab no longer lists the mail.
8. Don't show passwords, tokens, the client secret or real customer data.

For the restricted-scope video (Phase 4), also: open **Manage** on the account, turn on **Use my
inbox in automations**, show Google's second consent screen (reading email), send a test email from
the synthetic client's address, and show it on the client's **Emails** tab and the run of an email
automation (for example the lead reply, answered in the same thread). With the Email page, also open
**Email**, open that email, show the summary and suggested actions, add a quote from one (saving the
form yourself), and send a reply from the page.

## Restricted scopes and CASA (Phase 4)

- Verification of `gmail.readonly` / `gmail.modify` needs a **CASA Tier 2** assessment by an
  authorized lab (App Defense Alliance), renewed every 12 months. Google emails the steps after the
  restricted-scope submission.
- Keep for the assessor: this document, `docs/architecture.md`, the token cipher and key rotation
  (comma-separated keys in `INTEGRATIONS_ENCRYPTION_KEY`, newest first), the retention job, and the
  deployment's TLS and access controls.
- Until it passes, don't turn on inbox reading for real companies.

## Before going live

- [ ] Gmail API enabled, consent screen branded, domains verified in Search Console.
- [ ] Web client created with the production redirect URI; values set in Platform settings.
- [ ] `INTEGRATIONS_ENCRYPTION_KEY` set in the production environment and backed up.
- [ ] `/privacy` and `/data-deletion` reachable over HTTPS on `thebotslab.pt`.
- [ ] Brand + `gmail.send` verification submitted with the video; app switched to **In production**.
- [ ] (Phase 4) `gmail.modify` given its use or dropped from `GoogleScopes.inbox`.
- [ ] (Phase 4) Restricted scopes submitted; CASA passed; then inbox reading enabled (`GMAIL_INBOX_ENABLED=true`).
