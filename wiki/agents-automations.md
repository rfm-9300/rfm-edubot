---
updated: 2026-10-09
---

# Agents (automations) and Gmail

The opt-in Agents module: one automation engine fed by domain events, with lease-guarded background jobs, plus a Gmail integration. Runtime: [`docs/architecture.md`](../docs/architecture.md). Plan: [`docs/plan-agents-automations.md`](../docs/plan-agents-automations.md). Ops: the "Agents and Gmail" section of [`DEPLOYMENT_RUNBOOK.md`](../DEPLOYMENT_RUNBOOK.md).

## Agents (automations) and Gmail integration (PR #41, shipped 2026-10-01, `c1cf5c3`)

Built by a Cursor background agent (74 commits, about 30k lines) from `docs/plan-agents-automations.md`
through its Phase 5; `docs/architecture.md` has the runtime and the runbook's "Agents and Gmail"
section the ops. Rodrigo asked to merge it the same day. What matters later:

- **Opt-in, unlike every other module.** `DashboardModules.optIn` keeps `agents` out of a null module
  list (legacy tenants' "whole catalog"), so a company only gets it when the backoffice ticks it. At
  ship no company had it (0 of 5), so nothing automated runs in prod yet.
- **Domain event outbox** `domain_events`: CRM, booking, contact and chat repositories append an event
  after their own write, best effort (a failure is logged, never fails the write). The dispatcher
  claims and marks every event done even when no agent listens; record drawers read it as a timeline.
  TTLs: events 365 days, finished runs 180, notifications 90, `outbound_log` 30, email text 90.
- **Background work**: the agents scheduler (30 s tick), the email clean-up and the Gmail sync each
  take a Mongo lease (`scheduler_leases`) first. On `ApplicationStopping` the pipeline scope is now
  cancelled; unfinished messages and runs resume at the next boot.
- **Token budget**: agent AI steps count against the company's monthly budget, and `tenant_usage`
  splits usage `bySource` (pipeline, assistant, agents); the persona test now counts as assistant use.
- **Gmail is dormant in prod**: it needs a Google OAuth "Web application" client (VPS `.env` or
  Platform settings → Google) and `INTEGRATIONS_ENCRYPTION_KEY`, env-only and **not in the Mongo
  backups**, so a copy must live off the VPS or every account has to reconnect. Neither was set at
  ship (the app boots without them). Inbox reading stays behind `GMAIL_INBOX_ENABLED=false` until
  Google verifies the restricted read scopes and CASA passes; `gmail.modify` is requested but unused,
  so use it or drop it before that submission. Gmail push (Pub/Sub) isn't built; inboxes are polled.
- Merging it: `main` had moved 3 commits; the only conflict was `ClientServiceRepository` (domain
  events next to the new service lines). 551 tests green on the merge, local boot and smoke clean.

Production rollout (2026-10-01): backup `mongo-20261001T145548Z.archive.gz` right before the merge;
CI deployed `c1cf5c3`, Mongo not bounced, 0 `ERROR` lines, the new indexes created (the "Missing
config keys" WARN now lists the unset Google keys too).

Google approval check (2026-10-09, Rodrigo asked for a walkthrough; nothing changed yet). Sending
needs brand verification plus sensitive-scope verification of `gmail.send` (Google quotes 3–5
business days, with no security assessment); only inbox reading needs the restricted tier and the
yearly CASA. Three things stand in the way, and `docs/google-oauth-verification.md` misses all three:

- **The OAuth callback wasn't routed** (fixed the same day at Rodrigo's request). The VPS Caddy
  didn't proxy `/integrations*`, so `https://thebotslab.pt/integrations/google/callback` answered
  with the marketing site's 404 and no Gmail connect could finish. A `handle /integrations*` block
  now sends it to the app (with no parameters it answers 302 to
  `/app/?google=error&reason=missing_params`), and the runbook's Caddy block has it too.
- **The home page fails Google's rules.** Google wants the privacy policy on the home page's domain,
  and the home page to describe the app and link to that policy. Everything Google sees now uses
  `thebotslab.pt`, the primary domain: home page, `/privacy` and the redirect URI (the doc used to
  put `/privacy` on the dashboard host). The marketing home page (both domains serve the same studio
  page) still neither describes the CRM nor links any policy, and the name differs: `thebots.lab` on
  the site, `TheBotsLab` in the doc and on `/privacy`.
- **The consent screen is shared with Firebase sign-in.** Publishing status is per project, and
  Google sign-in for `/app` and the backoffice uses project `thebotslab` too, so moving it to Testing
  (the doc assumes Testing before verification) would lock every non-test user out of Google
  sign-in (agent inference; the console wasn't checked). Stay In production and click through
  Google's unverified-app screen while testing; each account counts toward a lifetime cap of 100
  new users until verification. Adding a redirect URI or renaming the app after approval means
  verifying again.
