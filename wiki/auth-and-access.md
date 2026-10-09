---
updated: 2026-10-09
---

# Sign-in and access

Who can sign in where, and what a session may open: Google sign-in for the backoffice and `/app`, the backoffice's admin list, and pointers to the access rules that live with other features.

## Who can open what

- Dashboard access is decided once per request, in the `jwt("dashboard")` validator, by `DashboardAccessPolicy`; the design and its reasons are in [architecture-review.md](architecture-review.md).
- An employee's own sign-in (`TENANT_EMPLOYEE`) reaches only `/app/api/me`, `/app/api/account/*` and `/app/api/portal/*`: [employee-portal-and-time-clock.md](employee-portal-and-time-clock.md).
- Switching company re-issues the token with the same expiry: [tenants-and-companies.md](tenants-and-companies.md).

## Google sign-in (Firebase project `thebotslab`, since 2026-09-28)

The Firebase/GCP project `thebotslab` serves Google sign-in for the backoffice and `/app`, and holds the off-box Mongo backups ([ops-and-deploy.md](ops-and-deploy.md)).

- **Backoffice sign-in with Google.** `/backoffice` offers "Continue with Google". The server
  verifies the Firebase ID token itself (Google JWKS, issuer/audience = project, Google provider,
  verified email in `ADMIN_EMAILS`) and issues the old admin JWT, so admin routes didn't change.
  Password login exists only while `ADMIN_PASSWORD_HASH` is set. It was removed from prod the
  same evening, after Rodrigo confirmed Google sign-in works, so Google is the only way in; if it
  breaks, setting the hash again in `.env` is the way back in. The backoffice's platform-settings
  override for that key (Mongo `platform_settings`) would also re-enable it, and it was empty.
  Allowed sign-in domains: `thebotslab.eu`, `thebotslab.pt` and both `www.` hosts (all four serve
  the backoffice).
- **Tenant dashboard sign-in with Google** (shipped 2026-09-29, `e888d1d`; verified in prod: the
  `googleUid` index exists, `/app/auth/config` offers Google, forged tokens get 401). Same project and verifier, without `ADMIN_EMAILS`: `FirebaseIdTokenVerifier.identify`
  accepts any verified Google account and the server picks the user. Rodrigo's choices: link
  automatically on the first Google sign-in when the verified email equals the dashboard email;
  each user can switch their password off ("Google only"), and it stays on until they do; users
  change their own password; no backoffice status/unlink/reset actions. Design: match by Firebase
  uid first (`dashboard_users.googleUid`, partial unique index on `{$type: "string"}` so users
  without Google never collide), then by email; a user already linked to another uid is refused, so
  an email match can't take the account over. Link, unlink and change password need the current
  password; turning the password off, or setting one again from Google only, needs a Google sign-in
  to the linked account at most 5 minutes old (proves Google works before it becomes the only way
  in). Password login answers a Google-only user like a wrong password (no account enumeration).
  The account UI is a top-bar avatar that opens a record drawer, not a Settings tab, because
  Settings is an optional module. Known gap: a Google-only user who loses their Google account needs
  a manual Mongo edit (command in the runbook). The mobile app stays password-only.
  Shipped 2026-09-30 (`8d3af97`): the backoffice's **Admins** page adds emails (Mongo
  `admin_emails`) on top of `ADMIN_EMAILS`. `RuntimeConfig`'s live `allowedEmails` is the union.
  Env emails are the break-glass list, so the page can't remove them, and nobody can remove
  themselves. The `admin-jwt` validator re-checks a Google session's `email` claim on every request,
  so removing someone signs them out at once instead of up to 24 h later.

Gotchas from setting it up:

- `firebase projects:create` rejects a display name with a dot (`thebots.lab` → 400); the ID was
  fine.
- Google sign-in can be turned on without the console. Put `auth.providers.googleSignIn` in
  `firebase.json` and run `firebase deploy --only auth`; it also provisions the OAuth brand.
  Authorized domains have no CLI command; use the Identity Toolkit admin API
  (`PATCH …/admin/v2/projects/<id>/config?updateMask=authorizedDomains`) with a `gcloud` token.
- New Firebase projects no longer authorize `localhost` by default, so local Google sign-in fails
  unless it is added.
