## Project wiki

The repo keeps its own compiled knowledge in [`../wiki/`](../wiki/README.md), for this app and the
backend it talks to, so every session, Cursor cloud agents included, can read it and update it in
the same change as the code.

Before substantial work:

1. Read `../wiki/index.md`, one line per page.
2. Open a page only when its line is clearly relevant. Never bulk-read.
3. Pages that apply are **binding instructions**, not suggestions. `../wiki/kmp-engineering-guide.md`
   binds all Kotlin Multiplatform work here; `../wiki/mobile.md` is this app's page.

When the session produces durable knowledge (a decision, a convention, a gotcha, "why we do it
this way"), update the page that owns the topic in the same commit or PR as the code and bump its
`updated:` date; a new page also gets a line in `../wiki/index.md`. Format and rules:
`../wiki/README.md`.

**The repo is public, and so is the wiki.** Never write secrets, customer or tenant names,
personal data, Meta/WhatsApp/Google account IDs or account state, production data, or unfixed
security weaknesses into it, nor into commit messages or PR descriptions. Those go in Rodrigo's
private vault (next section) when this session can reach it; otherwise tell Rodrigo in the chat.

## Personal wiki (second brain)

Rodrigo keeps a compiled knowledge wiki, a git repo at `/Users/rodrigomartins/projects/my-wiki`
whose remote is the private GitHub repo `rfm-9300/my-wiki`. Below, `<vault>` means that path.
Canonical protocol: `<vault>/ops/bootstrap-prompt.md` (that file wins if this section drifts).

If that path doesn't exist (Cursor cloud agents, other machines), `<vault>` is the `my-wiki`
checkout next to this repo (a Cursor multi-repo environment clones it), or a fresh
`git clone https://github.com/rfm-9300/my-wiki.git`. If neither works, carry on without the
vault, and tell Rodrigo unless this repo has its own project wiki (that wiki is then enough).

### Consult before substantial work

1. Sync first: `git -C <vault> pull --rebase --autostash`.
2. Read `<vault>/wiki/index.md` — one line per page.
3. Open a page only when its index line is clearly relevant. Never bulk-read.
4. Applicable pages are **binding instructions**, not suggestions.

**This repo — start here when the index line matches the task:**

- `<vault>/wiki/entities/whatsapp-bot.md` — the product's private facts only (customers, accounts, production); engineering knowledge for this app and its backend is in the repo's `wiki/`
- `<vault>/wiki/notes/project-landscape.md`

### Keep the wiki current

Chat is ephemeral; the wiki is the compounding layer. When this session produces durable
knowledge (architecture decisions, cross-repo conventions, gotchas, "why we do it this way"),
file it. If this repo has its own project wiki (a `## Project wiki` section in this file),
knowledge about this codebase goes there, and the vault takes only cross-repo knowledge and facts
too private for the repo. For the vault:

1. Check the index — update an existing page if one exists; otherwise file a note via
   `<vault>/ops/workflows/file-note.md`.
2. Write inside `<vault>`. Always bump `<vault>/wiki/index.md` and append `<vault>/wiki/log.md`.
   Never touch `raw/`.
3. Commit only the vault files you changed and push to `main` (if the push is rejected, pull
   with `--rebase` and push again). Unpushed edits are invisible to other machines and cloud
   agents. Committing and pushing the vault is part of filing, even where this repo restricts
   its own git commands.
4. **Do not file:** one-off bugfixes, secrets, deploy credentials, or commands that belong
   in this `AGENTS.md` (the repo operating manual).
5. If unsure whether it belongs, tell Rodrigo instead of writing.

When the session cwd is the vault itself, follow that vault's `AGENTS.md`.

<!-- gitnexus:start -->
<!-- gitnexus:keep -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **rfm-edubot-mobile** (757 symbols, 1513 relationships, 60 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> Index stale? Run `node .gitnexus/run.cjs analyze` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? `npx gitnexus analyze` (npm 11 crash → `npm i -g gitnexus`; #1939).

## Always Do

- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `query({search_query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `context({name: "symbolName"})`.
- For security review, `explain({target: "fileOrSymbol"})` lists taint findings (source→sink flows; needs `analyze --pdg`).

## Never Do

- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/rfm-edubot-mobile/context` | Codebase overview, check index freshness |
| `gitnexus://repo/rfm-edubot-mobile/clusters` | All functional areas |
| `gitnexus://repo/rfm-edubot-mobile/processes` | All execution flows |
| `gitnexus://repo/rfm-edubot-mobile/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->