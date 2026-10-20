---
updated: 2026-10-20
---

# Password vault

The platform team's password manager in the backoffice (Passwords in its menu): one shared vault,
encrypted in the browser with a master password the team shares, so the server, the database and its
backups only hold sealed data. Built 2026-10-20 at Rodrigo's request. Rodrigo chose this model over
encrypting on the server with the app's key (`INTEGRATIONS_ENCRYPTION_KEY`), which needs no extra
password but lets anyone with the server or a backoffice session read everything.

## How the crypto works

All of it runs in `backoffice/vault.js` with WebCrypto; the server never sees a password or plaintext.

- **The vault key** is 32 random bytes made in the browser of whoever creates the vault.
- **The master password** derives a key with PBKDF2-SHA-256 (600,000 iterations, a random 16-byte salt)
  that seals the vault key with AES-256-GCM. `vault.meta` stores the KDF parameters and that sealed key,
  and any backoffice session reads them, since unlocking needs them.
- **From the vault key**, HKDF-SHA-256 derives two independent values: the AES-256-GCM key that seals
  each entry (`thebots-vault-entries-v1`), and a 32-byte token (`thebots-vault-auth-v1`).
- **Entries** are `{ v: 1, title, username, password, url, notes }` as JSON, sealed with a fresh 12-byte
  IV each save. `vault.entries` keeps only the sealed bytes, plus who saved them and when.
- **The token** goes with every call that reads or changes entries (`X-Vault-Auth`). The server keeps its
  SHA-256 from the vault's creation and compares in constant time, so signing in to the backoffice alone
  can't download, overwrite or delete the entries. The token never changes; the master password does.
- **Changing the master password** needs the current one (the browser checks that it opens the sealed
  key and that the result is the vault key it holds), then seals the same vault key with a key from the
  new password and a new salt. The entries and the token stay as they are.
- **Locking** forgets the keys, zeroes the vault key's bytes, drops the opened entries and closes a
  password drawer: after 10 minutes without a key press or a tap anywhere in the backoffice, on Lock, on
  Log out, and when the page closes. Nothing of the vault goes to `localStorage`.

## Server side

- `admin/vault/`: `VaultRepository.kt` (`vault.meta`, one document `_id: "team"`, and `vault.entries`, in
  the platform database), `VaultService.kt` (checks sizes, KDF bounds and the token), `VaultRoutes.kt`
  (`/admin/api/vault`, behind `admin-jwt`, answers `Cache-Control: no-store`).
- What it checks, without decrypting anything: one vault (409 `vault_exists`); PBKDF2 with SHA-256 and
  100k to 10M iterations, a 16–64 byte salt; a 12-byte IV everywhere; a sealed vault key of exactly 48
  bytes (key and tag); entries of 17 bytes to 64 KiB; 2,000 entries at most; a 32-byte token.
- **Refusals answer 403, never 401** (`vault_locked` without the token, `vault_auth_failed` with a wrong
  one): `apiFetch` treats any 401 as an expired sign-in and logs the operator out.
- **A save can't silently undo someone else's:** the browser sends the `updatedAt` it read, and the
  server refuses with 409 `stale_entry` if the entry changed since; the page reloads the entries and
  shows the other version.

## Decisions

- **One shared vault and one master password**, shared in person, rather than a key per member
  (wrapped with each member's own password or public key, as team password managers do). Simpler, and
  enough for the team today; members would mean a new page and a migration of the sealed key.
- **No recovery and no reset from the UI.** Nobody can open the vault without the master password, and
  letting any admin wipe it would let one admin destroy the team's passwords. If the master password is
  lost, reset by hand: delete the documents of `vault.meta` and `vault.entries` in the platform
  database, and the next visit to Passwords creates a new vault. The old entries are gone.
- **Backups hold only ciphertext:** both collections live in the platform database, whose archive
  backs them up with the rest ([mongo-databases.md](mongo-databases.md)).
- **The page needs a secure context** (https, or `localhost`): browsers only offer `crypto.subtle`
  there, and the page says so instead of failing.
- **The vault's fields ask password managers to stay out** (`autocomplete="off"`, `data-1p-ignore`,
  `data-lpignore`, `data-bwignore`), and no field uses the autocomplete value for a new password, which
  GitGuardian flags as a secret ([gotchas.md](gotchas.md)).

## Limits

A web page can't defend against whoever controls the server it is served from: someone who could
change the backoffice's JavaScript could capture the master password on the next unlock. The vault
protects against a leaked database or backup, and against a backoffice admin who doesn't know the
master password; it is no stronger than the server and the browsers that open it. Like any vault a
password opens, it is as strong as that password, since the sealed key and the KDF parameters have to
be readable to unlock: hence the 600,000 iterations, and the page asking for at least 12 characters and
suggesting a sentence.
