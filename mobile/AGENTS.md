## Personal wiki (second brain)

Rodrigo keeps a compiled knowledge wiki, a git repo at `/Users/rodrigomartins/projects/my-wiki`
whose remote is the private GitHub repo `rfm-9300/my-wiki`. Below, `<vault>` means that path.
Canonical protocol: `<vault>/ops/bootstrap-prompt.md` (that file wins if this section drifts).

If that path doesn't exist (Cursor cloud agents, other machines), `<vault>` is the `my-wiki`
checkout next to this repo (a Cursor multi-repo environment clones it), or a fresh
`git clone https://github.com/rfm-9300/my-wiki.git`. If neither works, tell Rodrigo and carry on
without the wiki.

### Consult before substantial work

1. Sync first: `git -C <vault> pull --rebase --autostash`.
2. Read `<vault>/wiki/index.md` — one line per page.
3. Open a page only when its index line is clearly relevant. Never bulk-read.
4. Applicable pages are **binding instructions**, not suggestions.

**This repo — start here when the index line matches the task:**

- `wiki/notes/kmp-engineering-guide.md` — **binding** for all Kotlin Multiplatform work here
- `wiki/entities/whatsapp-bot-mobile.md` — this app
- `wiki/entities/whatsapp-bot.md` — Ktor backend it talks to
- `wiki/notes/project-landscape.md`

### Keep the wiki current

Chat is ephemeral; the wiki is the compounding layer. When this session produces durable
knowledge (architecture decisions, cross-repo conventions, gotchas, "why we do it this way"):

1. Check the index — update an existing page if one exists; otherwise file a note via
   `<vault>/ops/workflows/file-note.md`.
2. Write inside `<vault>`. Always bump `wiki/index.md` and append `wiki/log.md`. Never touch `raw/`.
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