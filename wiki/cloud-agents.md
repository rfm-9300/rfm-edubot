---
updated: 2026-10-09
---

# Cursor cloud agents on this repo

How Cursor cloud agents work on this repo, what their environment has, and how their PRs get merged.

Part of the work lands as PRs from Cursor cloud agents (branches `cursor/<topic>-<4 hex>`, opened
under `rfm-9300`): #2 to #13 (August to 2026-09-22), #41, #43, #44, #48, #51, #52, #53, #54, #55 and #72.
Local sessions push straight to `main`. What a session should know:

- **The environment is configured in Cursor, not in the repo.** `main` has no
  `.cursor/environment.json`; the AGENTS.md section "Cursor Cloud specific instructions" (PR #42,
  merged 2026-10-03, `fc417b2`, Markdown only so no deploy) documents it: Docker plus the Gradle JDK
  20 toolchain, MongoDB and `./gradlew run` on boot, Testcontainers pinned to Docker API 1.44 through
  `~/.docker-java.properties` (the Engine 29 problem in [gotchas.md](gotchas.md)), a 65536 nofile
  ulimit for Mongo 7 in `/etc/docker/daemon.json`, a placeholder `.env` (backoffice password
  `local-dev`), and `mobile/` out of scope. Cursor resolves an in-repo `.cursor/environment.json`
  before any saved personal or team environment, so committing one replaces that setup.

- **The wiki is in the repo (since 2026-10-09).** Cloud agents couldn't reach Rodrigo's private wiki (a `rfm-9300/my-wiki` checkout needs a multi-repo environment, which rules out long-running agents), and none of the agents behind #51 to #55 wrote to it. So this repo's engineering knowledge moved into `wiki/`, which agents read and update in the same PR as their code (`AGENTS.md` → Project wiki). The repo is public: customer, account and production facts stay in the private wiki, and an agent that learns one tells Rodrigo in the chat instead of writing it here.

- **Gotcha (fixed 2026-10-05): the root `.gitignore`'s `data/` was unanchored**, so it ignored every
  directory named `data`, not only the local PDF store `./data/pdfs`. `git add -A` then silently left
  such a directory out: PR #44's whole `mobile/core/data` module never reached GitHub, and its Mobile CI
  failed with "Configuring project ':core:data' without an existing directory". PR #44's merge
  anchored the rule as `/data/`.
- **Agent PRs reviewed on 2026-10-03** (Rodrigo asked to merge all open agent work):
  - #42 merged (above).
  - #44, "Bring the KMP mobile app back up to the backend it talks to" (about 9.9k lines): blocked
    by the gotcha above on 2026-10-03, then merged on 2026-10-05 (`977c2d3`); details on
    [mobile](mobile.md).
  - #14, "Add Cloud Agent dev environment" (draft, 2026-09-22): closed unmerged as superseded by #42.
    Its in-repo `.cursor/environment.json` installs MongoDB 8 natively and writes a `.env` with
    backoffice password `admin123`; by the resolution order above it would have replaced the saved
    environment that #42 documents and that the #43 and #48 agents ran their tests in.
  - #15, "Test dashboard AI assistant and render its markdown" (draft, 2026-09-22): closed unmerged
    as superseded. It conflicted with `main` in five files. It found the raw-asterisks problem three
    days before `10e5ee5` fixed it differently (`renderChatText`: bold only, under `pre-wrap`); its
    renderer also handled lists, headings and inline code, which the assistant page lacked until #55
    (2026-10-08). Its
    `DashboardAssistantServiceTest` targeted the assistant's old hand-written tool loop, since
    replaced by the shared `ToolLoop`, and `main` has its own `DashboardAssistantToolPolicyTest`.
    Its production pass found the assistant has no tools for the inbox, contacts, settings,
    persona, Serviços, suppliers, payments or Instagram; #55 (2026-10-08) added customer chats,
    services, bills to pay, suppliers and employees.
- **Agent PRs merged on 2026-10-06** at Rodrigo's request: #51 and #52 ([dashboard-ui.md](dashboard-ui.md),
  [crm-directories.md](crm-directories.md)). Neither agent wrote to Rodrigo's private wiki, so the local
  session that merged them filed their work there. Both conflicted with
  that morning's `17425b5`. The session merged `main` into each agent branch (no rebase or
  force-push), resolved the conflicts, ran the suite and a headless-Chrome pass, pushed to the agent
  branch, marked the PR ready, waited for Test and GitGuardian, and merged with
  `gh pr merge --merge --match-head-commit <SHA>`. A short SHA fails there with a GraphQL error, so it
  needs the full one. One PR at a time: every merge to `main` deploys, and the next branch needs
  `main` merged in again.
- **Agent PRs merged on 2026-10-08** at Rodrigo's request, Persona first because he ranked it most
  important: #54, then #55, then #53 ([ai-persona-and-bot.md](ai-persona-and-bot.md),
  [ai-assistant.md](ai-assistant.md), [employee-portal-and-time-clock.md](employee-portal-and-time-clock.md)).
  None of the three agents read or wrote Rodrigo's private wiki.
  Same flow as 2026-10-06, with the conflicts worked out ahead of time: in throwaway worktrees the
  session simulated `main` after each merge, resolved #55 and #53 against it, and tested the final
  combination (the full suite, 769 tests; both walkthroughs, Persona 40 of 40 and assistant 16 of 16;
  a small time clock browser check) against a throwaway Mongo and the stand-in model. Once a real merge
  landed and `main`'s tree matched the simulated one, it rebuilt the next branch's merge from the
  tested tree with `git commit-tree`, so what CI tested is what had been verified locally.
- **Agent environments differ:** #51's agent reported no Docker in its VM and ran no Kotlin tests,
  while #52's ran the full suite (577 tests), and #53's, #54's and #55's did too (638, 700 and 643
  tests on their own branches). Don't assume an agent PR was tested beyond what its body says; CI's
  Test job is the backstop. #72's VM (2026-10-09) had none of the documented setup: no Docker, no
  boot script, no `.env`. With sudo, `apt-get install docker.io`, `dockerd` in the background, the
  65536 nofile ulimit in `/etc/docker/daemon.json` and `api.version=1.44` in
  `~/.docker-java.properties` gave it Mongo 7 and Testcontainers; `TEST_MONGO_URI` pointed at that
  Mongo container is faster than a container per run. The backoffice password needs a BCrypt
  `ADMIN_PASSWORD_HASH` in the hand-written `.env`.
- About 25 Dependabot PRs (2026-09-25 and 2026-10-02) are unreviewed, among them Kotlin 2.4.20,
  Ktor 3.6.0, the Mongo driver 5.12, kotest 6, Testcontainers 1.21.4 and, for mobile, AGP 9.4.1.
