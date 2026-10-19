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

Sending needs brand verification plus sensitive-scope verification (Google quotes 3–5 business
days, with no security assessment). Inbox reading needs restricted-scope verification and a yearly
**CASA** security assessment; it stays off (the scopes are never requested) until that is done.
`gmail.readonly` + `gmail.modify` are asked for with incremental consent, so companies that only
send never see them. Branding comes first: Google opens a scope review only once the branding is
verified and published.

## What Google checks first

Brand verification reads the consent screen and the pages it links:

- **Home page**: `https://thebotslab.pt/crm/`, a product page in the marketing site's repo
  (`websites-thebots`, `src/pages/crm.astro`). Google wants a public page on the privacy policy's
  domain that describes the app (a login page alone doesn't count), carries the app name, and links
  the same privacy policy URL as the consent screen. The studio landing page at
  `https://thebotslab.pt/` describes the studio rather than the app and links no policy, so it can't
  be the home page.
- **App name**: `TheBotsLab`, written exactly so on the consent screen, the home page and `/privacy`.
  Renaming the app after approval means verifying again.
- **Domains**: every authorized domain must be verified in Search Console by an account that is also
  an Owner or Editor of the project. Authorized domains include the domains of every web client's
  redirect URIs and JavaScript origins, so the Firebase sign-in client's `thebotslab.firebaseapp.com`
  counts too. If the branding check flags it, move Firebase's auth domain onto `thebotslab.pt` by
  proxying `/__/auth/` ([Firebase's redirect best practices](https://firebase.google.com/docs/auth/web/redirect-best-practices)).
- **Support email**: the dropdown offers only the signed-in account and the Google Groups it
  manages. To show `hello@thebotslab.pt`, make that account an Owner or Editor of the project and do
  the console steps signed in as it.

## Google Cloud project

1. Use the Firebase project **thebotslab** (one brand on the consent screen for sign-in and Gmail).
   Google sign-in for `/app` and the backoffice uses the same consent screen, so every change below
   shows there too.
2. **APIs & Services > Library**: enable the **Gmail API**.
3. **Audience**: user type External, publishing status **In production**. Never switch to Testing:
   publishing status is per project, and Testing would lock every Google account that isn't on the
   test-user list out of Google sign-in.
4. **Branding** (Google Auth Platform > Branding):
   - App name `TheBotsLab`, support email `hello@thebotslab.pt`, logo (120x120, the brand mark).
   - Home page `https://thebotslab.pt/crm/`, privacy policy `https://thebotslab.pt/privacy`. Terms of
     service are optional; we have none.
   - Authorized domain: `thebotslab.pt`.
   - Developer contact: `hello@thebotslab.pt`.
   - Press **Verify Branding**: an automated check that usually takes minutes. If it lists issues, fix
     them and run it again, or request additional review (2–3 business days). Then press **Publish
     branding** within 7 days, or the check has to run again. Branding can't be edited while a check
     runs, and changing the name, logo or URLs later makes a draft that needs verifying again.
5. **Data access**: add `gmail.send` next to the sign-in scopes already listed. Add `gmail.readonly`
   (and `gmail.modify`, if it stays) only when the restricted-scope submission is ready: declaring a
   restricted scope pulls the submission into the restricted review and CASA.
6. **Clients > Create client > Web application**, named `Gmail integration` (separate from the
   Firebase sign-in client). Authorized redirect URI: `https://thebotslab.pt/integrations/google/callback`;
   no JavaScript origins. Keep localhost and tunnel URLs out of this project and use another project
   for local work: a redirect URI added after approval means verifying again, and Google suggests
   removing clients that aren't production-ready from the project it verifies.
7. Put the client in the app: backoffice **Platform settings > Google** (`GOOGLE_OAUTH_CLIENT_ID`,
   `GOOGLE_OAUTH_CLIENT_SECRET`, `GOOGLE_OAUTH_REDIRECT`) or the same names as environment variables,
   plus `INTEGRATIONS_ENCRYPTION_KEY` (env only: `openssl rand -base64 32`). Without the key the
   Gmail row stays hidden and connecting answers 503.

## Before approval

- Connecting Gmail shows Google's "Google hasn't verified this app" screen first; testers continue
  through **Advanced**. The demo video may show it.
- Google lets at most 100 new users past that screen in total, so connect only test accounts, and
  don't onboard real companies until the `gmail.send` review has passed.
- Testing mode (a test-user list, refresh tokens that expire after 7 days) isn't an option here; see
  Audience above.

## Exact use of each scope (for the verification form)

**`gmail.send`**: TheBotsLab is a CRM for small service businesses. A company admin connects the
company's Gmail account so the business can send its quotes and invoices (as PDF attachments) and
the messages of automations it sets up (for example, a reminder before an invoice is due) from its
own address. We send only emails that a signed-in user of that company sends from the dashboard
(the "Send by email" button on a quote or invoice, the "Send a test email" button in Settings) or
that an automation the company created and turned on sends. Each sent email is shown on the
client's record. We never read the mailbox with this scope. No narrower scope can send: the other
Gmail scopes that can (`gmail.compose`, `gmail.modify`, `https://mail.google.com/`) are restricted
and give more access.

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

1. Use a test Google account, a company whose admin user has that account's email (the first
   Google sign-in links them), and a synthetic client whose email you control. English UI
   (**Settings > Language**), English browser.
2. Show the browser address bar throughout: the consent screen URL must show the OAuth client ID.
3. Start at `https://thebotslab.pt/app/` and sign in with **Continue with Google**: Google asks to
   see the sign-in flow when the app uses Google sign-in.
4. In **Settings > Channels**, press **Connect Gmail**. Continue past Google's unverified-app screen,
   then show the app name, the requested permissions ("Send email on your behalf") and press
   **Continue**.
5. Back in Settings, show the connected account, open **Manage**, set a sender name and signature,
   press **Send a test email**, and show it arriving in the test inbox.
6. Open a quote, press **Send by email**, show the draft (recipient, subject, text, PDF attached),
   send it, then show the email in the recipient's inbox and on the client's **Emails** tab.
7. Open **Agents**, show an automation with an **Email** step (for example the invoice reminder) and
   the email it sent.
8. Back in Settings, **Disconnect** the account and confirm; show that the row is gone and the
   client's Emails tab no longer lists the mail.
9. Don't show passwords, tokens, the client secret or real customer data. Upload the video to
   YouTube as Unlisted.

For the restricted-scope video (Phase 4), also: open **Manage** on the account, turn on **Use my
inbox in automations**, show Google's second consent screen (reading email), send a test email from
the synthetic client's address, and show it on the client's **Emails** tab and the run of an email
automation (for example the lead reply, answered in the same thread). With the Email page, also open
**Email**, open that email, show the summary and suggested actions, add a quote from one (saving the
form yourself), and send a reply from the page.

## Submitting

1. Publish the branding first (Google Cloud project, step 4): the Verification Center takes a data
   access request only after that.
2. **Verification Center** > data access: paste the text from "Exact use of each scope" for each
   sensitive or restricted scope, say why a narrower scope wouldn't do, give the video's YouTube
   link, and add up to three links (home page, `/privacy`, `/data-deletion`).
3. Google quotes 3–5 business days for sensitive scopes and several weeks for restricted ones. Its
   questions arrive by email at the support and developer contact addresses, and the review pauses
   until we answer.

## Restricted scopes and CASA (Phase 4)

- Verification of `gmail.readonly` / `gmail.modify` needs a **CASA** security assessment by a lab
  the App Defense Alliance authorized, renewed every 12 months. The free self-scan route is
  deprecated: the lab verifies the evidence (assurance level AL1, or AL2 for high-risk apps) and
  charges for it; Google doesn't. Google emails the steps after the restricted-scope submission.
- Under **What features will you use?**, pick the CRM use: Google's Workspace user data policy lists
  "applications for customer relationship management" among the approved uses of Gmail scopes.
- Before submitting, add the Email page and the inbox automations to the home page
  (`src/pages/crm.astro` in `websites-thebots`); it describes only sending today.
- Keep for the assessor: this document, `docs/architecture.md`, the token cipher and key rotation
  (comma-separated keys in `INTEGRATIONS_ENCRYPTION_KEY`, newest first), the retention job, and the
  deployment's TLS and access controls.
- Until it passes, don't turn on inbox reading for real companies.

## Before going live

- [ ] Home page `https://thebotslab.pt/crm/` deployed: describes the app and links `https://thebotslab.pt/privacy`.
- [ ] Gmail API enabled; `thebotslab.pt` verified in Search Console by an Owner or Editor of the project.
- [ ] Branding verified and published (app name `TheBotsLab`, home page, privacy policy, support email).
- [ ] Web client created with the production redirect URI only; values set in Platform settings.
- [ ] `INTEGRATIONS_ENCRYPTION_KEY` set in the production environment and backed up.
- [ ] `/privacy` and `/data-deletion` reachable over HTTPS on `thebotslab.pt`.
- [ ] `gmail.send` data access submitted with the video, and approved.
- [ ] (Phase 4) `gmail.modify` given its use or dropped from `GoogleScopes.inbox`; home page describes inbox reading.
- [ ] (Phase 4) Restricted scopes submitted; CASA passed; then inbox reading enabled (`GMAIL_INBOX_ENABLED=true`).
