## Personal wiki (second brain)

Rodrigo keeps a compiled knowledge wiki at `/Users/rodrigomartins/projects/my-wiki`.
Canonical protocol: `/Users/rodrigomartins/projects/my-wiki/ops/bootstrap-prompt.md`
(that file wins if this section drifts).

### Consult before substantial work

1. Read `/Users/rodrigomartins/projects/my-wiki/wiki/index.md` — one line per page.
2. Open a page only when its index line is clearly relevant. Never bulk-read.
3. Applicable pages are **binding instructions**, not suggestions.

**This repo — start here when the index line matches the task:**

- `wiki/notes/kmp-engineering-guide.md` — **binding** for all Kotlin Multiplatform work here
- `wiki/entities/whatsapp-bot-mobile.md` — this app
- `wiki/entities/whatsapp-bot.md` — Ktor backend it talks to
- `wiki/notes/project-landscape.md`

### Keep the wiki current

Chat is ephemeral; the wiki is the compounding layer. When this session produces durable
knowledge (architecture decisions, cross-repo conventions, gotchas, "why we do it this way"):

1. Check the index — update an existing page if one exists; otherwise file a note via
   `/Users/rodrigomartins/projects/my-wiki/ops/workflows/file-note.md`.
2. Write with absolute paths under `/Users/rodrigomartins/projects/my-wiki/`. Always bump
   `wiki/index.md` and append `wiki/log.md`. Never touch `raw/`.
3. **Do not file:** one-off bugfixes, secrets, deploy credentials, or commands that belong
   in this `AGENTS.md` (the repo operating manual).
4. If unsure whether it belongs, tell Rodrigo instead of writing.

When the session cwd is the vault itself, follow that vault's `AGENTS.md`.

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **rfm-edubot-mobile** (757 symbols, 1513 relationships, 60 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> Index stale? Run `node .gitnexus/run.cjs analyze` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? `npx gitnexus analyze` (npm 11 crash → `npm i -g gitnexus`; #1939).

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows. For regression review, compare against the default branch: `detect_changes({scope: "compare", base_ref: "main"})`.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `query({search_query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `context({name: "symbolName"})`.
- For security review, `explain({target: "fileOrSymbol"})` lists taint findings (source→sink flows; needs `analyze --pdg`).

## Never Do

- NEVER edit a function, class, or method without first running `impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.
- NEVER commit changes without running `detect_changes()` to check affected scope.

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