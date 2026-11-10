---
updated: 2026-11-04
---

# Company email addresses (Amazon SES)

Each company can get its own address on the platform's mail domain, forward its mailbox there and
send its email from it: why it was built this way, and the traps found building it. The runtime and
its diagram are in [`docs/architecture.md`](../docs/architecture.md#company-email-addresses), the AWS
setup in [`DEPLOYMENT_RUNBOOK.md`](../DEPLOYMENT_RUNBOOK.md) ("Company email addresses (Amazon SES)"),
the screens in [`design-system/patterns.md`](../design-system/patterns.md). The Email page itself is in
[inbox-and-channels.md](inbox-and-channels.md); the Gmail integration it replaces as the way in, in
[agents-automations.md](agents-automations.md).

## Why forwarding, and why SES (built 2026-11-04)

Connecting Gmail needs Google's verification: `gmail.send` is a sensitive scope, and reading the inbox
is restricted, with a yearly paid CASA assessment. Rodrigo chose instead a per-company address that
the company forwards its own mailbox to, so no Google consent or review is involved and any mail
provider works. Choices he made, with the alternatives he was offered:

- **Amazon SES in eu-west-1 (Ireland)** for both directions, over Mailgun (EU), Postmark or a
  self-hosted inbound SMTP on the VPS. SES is the cheapest at volume and keeps mail in the EU; the
  cost is more setup (receipt rule, S3, SNS, IAM, leaving the sandbox).
- **Gmail connect hidden, its code kept.** While `MAILBOX_DOMAIN` is set, Settings doesn't offer it;
  an account a company already connected is still listed so it can be disconnected.
- **The company's bot address is the sender**, with Reply-To the company's own address (prefilled
  from the address on its documents), so replies land in its own mailbox, which forwards them back
  into the thread. Sending from the company's own domain (per-company DKIM) was left for later.

## Decisions that shape the code

- **The address is the slug, a dot and six random characters** (`MailboxAddresses`; no 0/o/1/l/i).
  The suffix keeps the addresses from being enumerated; it isn't a secret, since every email sent
  from the address shows it. Unique by address and one per company (partial unique indexes on
  `integration_connections`, `provider: mailbox`). An operator may create it: nothing is read until
  the company turns forwarding on in its own mailbox.
- **S3 action plus SNS, not SNS's own action.** SES's SNS action carries the email inside the
  notification only up to 150 KB and bounces anything bigger, which supplier bills with PDFs are. The
  receipt rule stores the email in S3 and announces it on the topic; the app reads it from S3 and
  deletes it. `MailboxInbound` still reads an SNS action's inline content if one is set up.
- **Routing uses the notification's envelope recipients, never `To`.** The `To` of forwarded mail is
  the company's own address, not ours, and the stored raw email doesn't name the envelope recipient.
  That is also why there is no "sweep the bucket" fallback: an object found there can't be routed.
  SNS's delivery retries and the bucket's lifecycle rule cover an app restart instead.
- **Every SNS message is verified** (`SnsVerifier`): only the configured topics, a signing
  certificate fetched over HTTPS from `sns.<region>.amazonaws.com`, SHA1/SHA256 with RSA by signature
  version, and nothing older than 6 hours. A subscription is confirmed only on SNS's own host.
  `SNS_TRUSTED_CERT_URL` exists for the local stand-in and must never be set in production.
- **What is dropped**: SES's virus or spam verdict, and DMARC failing for a domain that asks receivers
  to reject (a forged sender). DMARC failing under `p=none` is kept, because forwarding breaks SPF.
- **Hand forwards are unwrapped.** When someone at the company forwards a single email (from its
  addresses, its forwarding mailbox or a teammate's sign-in), the email is kept as from whoever wrote
  it: the attached `message/rfc822`, or the `From:` in the quoted header block (Gmail, Outlook, Apple
  Mail, in several languages). `forwardedBy` records who forwarded it. Other mail from the company's
  own addresses is its own and isn't kept, as in Gmail's inbox sync.
- **Gmail's forwarding confirmation is surfaced, never acted on.** Gmail emails a code before it
  forwards anywhere. The app keeps the code, Gmail's link and the asking account for the setup
  screens, only for mail from `forwarding-noreply@google.com` with Google's DKIM valid, and only links
  on Google's mail settings hosts. Confirming by itself would let anyone route their mail into a
  company's inbox.
- **No AWS SDK.** Three calls (SES SendEmail, S3 GetObject and DeleteObject) are signed with a small
  SigV4 (`AwsSigV4`), tested against the two examples in AWS's documentation, instead of the SDK's jars.
- **One store, shared with inbox sync.** `ReceivedEmails` keeps and announces received mail for both
  sources, and `MailHeuristics` is the one copy of the machine-mail rules.

## Gotchas

- **SES replaces the `Message-ID`** (and the `Date`) of what it sends. Replies name SES's
  `<{MessageId}@{region}.amazonses.com>` (`email.amazonses.com` in us-east-1), so that is what's kept
  as `messageIdHeader` for sent mail; threading also matches a reference by the SES id alone, in case a
  mail program names another of SES's domains.
- **Bcc must not be in the MIME for SES.** Gmail's `messages.send` reads the recipients from the
  headers and drops Bcc; SES sends the message as written, so a Bcc header would show the hidden copy
  to everyone. `MimeMessageBuilder.bccHeader` is off for SES, and the recipients go in `Destination`.
- **A timeout after the request reached SES may have sent the email**: it is `send_in_doubt` and the
  outbound log stays claimed. Only a refused connection is retried.
- **`mailbox` vanishes from `/app/api/integrations` when it's all defaults** (`encodeDefaults` off,
  [gotchas.md](gotchas.md)), so the frontend treats a missing object as "not configured".
- **The tests make SNS's key at runtime** with the JDK's `keytool` (`FakeAws`), and the local stand-in
  with `openssl`: a private key committed to this public repo, even a test one, trips GitGuardian.

## Not built yet

- SES's bounce and complaint notifications aren't consumed; bounces reach the company's Email page as
  automatic mail through SES's feedback forwarding to the sending address.
- Newsletters a mailbox forwards are kept (flagged automatic); Gmail's inbox sync drops strangers'
  promotions by Gmail's categories, which forwarded mail doesn't carry.
- Replies the company sends from its own mailbox aren't seen unless it adds the address in Bcc.
- Sending from the company's own domain.
