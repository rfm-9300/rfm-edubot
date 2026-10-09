# Project wiki

Compiled knowledge about this codebase: why it is shaped the way it is, feature decisions,
conventions and gotchas. It lives in the repo so that every session, Cursor cloud agents included,
can read it and update it in the same change as the code. Start at [index.md](index.md).

## How it relates to the other docs

| Where | What it holds |
|---|---|
| `AGENTS.md` / `CLAUDE.md` | The operating manual: commands, deploy, run local, guardrails |
| `docs/architecture.md` | Structure and diagrams |
| `docs/plan-*.md` | Plans written before a feature was built |
| `design-system/` | Binding rules for the web UI |
| `DEPLOYMENT_RUNBOOK.md` | How to deploy and operate production |
| `wiki/` (this folder) | Decisions and their reasons, gotchas, and the history that explains today's code |

Link to those files instead of copying them.

## The repo is public, and so is this wiki

Never write any of these here, nor in commit messages or PR descriptions:

- secrets, credentials, tokens or keys, or where a live secret is kept;
- customer or tenant names and slugs, or anything else that identifies a customer's business;
- personal data: people's names, phone numbers, emails, addresses;
- Meta, WhatsApp, Google or other account and object IDs (app, WABA, phone number, portfolio), and
  account state such as verification, billing or review status;
- production data: a customer's records, or counts tied to a named customer;
- security weaknesses that aren't fixed yet.

Write the engineering lesson without them ("one tenant", "a business-owned number"). Rodrigo keeps
those facts in his private wiki: a session that can reach it files them there (root `AGENTS.md`,
"Personal wiki"), and a session that can't, such as a Cursor cloud agent, tells Rodrigo in the chat.

## Reading

1. Read [index.md](index.md), one line per page.
2. Open a page only when its line is clearly relevant to the task. Never bulk-read.
3. A page that applies is a binding instruction, not a suggestion.
   [kmp-engineering-guide.md](kmp-engineering-guide.md) binds all work in `mobile/`.

## Writing

When a session produces durable knowledge about this codebase (an architecture decision, a
convention, a gotcha, the reason something is done a certain way), record it in the same commit or
PR as the code:

1. Update the page that owns the topic. Create a page only for a topic no page covers, and then add
   its line to [index.md](index.md).
2. Lead with the current state. Keep history (what changed, when, which commit or PR) where it
   explains the present, and when something stops being true, correct it and say what changed
   instead of leaving the old claim standing.
3. Bump the page's `updated:` date.
4. Keep index lines short and stable: they describe a page's scope, not its latest change, so most
   updates don't touch the index.

Don't file one-off bugfixes, command cheat sheets (they belong in `AGENTS.md` or the runbook), or
anything on the public-repo list above.

There is no log file. Git history is the log (`git log -- wiki/`), because a shared append-only file
would conflict between every pair of parallel PRs.

## Page format

- One flat folder, kebab-case filenames, standard Markdown with relative links
  (`[billing](billing-and-pdfs.md)`), so pages read the same on GitHub and in an editor.
- Frontmatter holds `updated: YYYY-MM-DD`, plus `provenance:` when a page is synthesis rather than
  checked against the code (the KMP guide comes from web research, for example).
- An H1 title, then one short paragraph saying what the page covers and where neighbouring topics
  live.
- Sections name the change that introduced them where it helps (`PR #55`, commit `b570df5`), so
  `git show` finds the details.
