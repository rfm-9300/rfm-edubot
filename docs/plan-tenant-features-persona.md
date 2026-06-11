# Implementation Plan — Per-Tenant Feature Visibility & Bot Persona

Status: **Phase 1–3 implemented** · Owner: Rodrigo · Last updated: 2026-06-08

Two related dashboard features:

1. **Operator-controlled feature visibility** — the operator (`/admin`) decides which modules a
   tenant sees in their dashboard (`/app`). E.g. a tenant that should only see *Conversations*.
2. **Bot persona** — the tenant (`/app`) supplies the bot's "personality" as a **single compact
   instruction file** that is injected at response time, but is **built/updated gradually** from
   incremental inputs via an offline synthesis step (so chat-time tokens stay low).

The good news: both have clean seams in the current code (see §2.1 and §3.3).

---

## 1. Surfaces recap (who edits what)

| Surface | Auth | Role | Touched by |
|---|---|---|---|
| `/admin` | `admin-jwt` | Operator / platform admin | Feature 1 (toggle modules), optional persona view |
| `/app` | `dashboard` JWT (`DashboardUser`) | Tenant / client | Feature 1 (consumes module list), Feature 2 (edits persona) |
| `/backoffice` | operator | — | not in scope |

---

## 2. Feature 1 — Per-tenant module visibility

### 2.1 Current state (the seam already exists)

`dashboard/DashboardRoutes.kt:305` already computes the visible module list, hardcoded from
`agentType`:

```kotlin
private fun modulesFor(tenant: Tenant): List<String> = buildList {
    addAll(listOf("overview", "conversations", "contacts", "settings"))
    if (tenant.agentType == "CRM_V1") addAll(listOf("clients", "quotes", "invoices", "catalog"))
}
```

It's returned by `GET /app/api/me` (`:98`) as `modules`, and the `/app` frontend hides nav
sections accordingly. The CRM routes additionally **enforce** access with
`?.takeIf { it.tenant.agentType == "CRM_V1" } ?: Forbidden` (e.g. `:194`).

So the work is: make this a **persisted, operator-editable per-tenant set**, and make the
enforcement read it (not just `agentType`).

### 2.2 Module catalog

Canonical module ids (single source of truth, new `dashboard/Modules.kt`):

| Module | Always available? | Requires |
|---|---|---|
| `overview` | yes | — |
| `conversations` | yes | — |
| `contacts` | yes | — |
| `settings` | yes (so persona/account stay reachable) | — |
| `persona` | optional | — (new, see Feature 2) |
| `clients` `quotes` `invoices` `catalog` | optional | `agentType == "CRM_V1"` |

**Effective modules = `enabledModules ∩ availableForAgentType`.** A module can't be enabled if the
tenant's `agentType` doesn't support it. Recommend keeping `settings` un-disableable so the
tenant can always reach account/persona config (Decision D1).

### 2.3 Data model

`tenant/model/TenantModels.kt` — add:

```kotlin
val enabledModules: List<String>? = null,   // null = legacy default (derive from agentType)
```

- `null` → fall back to the current `modulesFor` behavior (back-compat; no migration needed for
  existing tenants).
- non-null → the operator-chosen set, intersected with the catalog + agentType availability.

`TenantRepository` read/write mapping updated; `TenantDto` (admin) exposes `enabledModules`.

### 2.4 Backend changes

- `modulesFor(tenant)` → reads `enabledModules` when present, else legacy; always intersects with
  the catalog and agentType availability.
- **Enforcement helper** in `DashboardRoutes.kt`:
  ```kotlin
  fun DashboardContext.requireModule(id: String): Boolean   // false ⇒ caller responds 403
  ```
  Replace the scattered `takeIf { agentType == "CRM_V1" }` guards with `requireModule("quotes")`
  etc., so disabling a module blocks the API, not just the nav. (Keep the agentType check folded
  into availability.)
- Apply guards to `conversations`, `contacts`, and each `crm/*` route.

### 2.5 Operator UI (`/admin` — `index.html` + `app.js`)

- In the tenant create/edit form, add a **Modules** checklist. Only show toggles for modules the
  tenant's `agentType` supports; always-on modules render as disabled/checked.
- Persist via the existing `POST/PUT /admin/api/tenants` (extend `TenantCreateRequest` /
  `TenantUpdateRequest` with `enabledModules`), or a focused `PUT /admin/api/tenants/{slug}/modules`.
- On save, the code already calls `tenantRegistry.put` + `pipelineFactory.evict`; the next
  `/app/api/me` reflects the change immediately (tenant just needs to refresh).

### 2.6 Tenant UI (`/app`)

Largely **no change** — it already renders nav from the `modules` array returned by `/me`. Verify
it gracefully handles a minimal set (e.g. only `conversations` + `settings`) without dead links.

### 2.7 Migration

None required. Existing tenants keep `enabledModules = null` → identical behavior. Operators
opt-in per tenant by saving an explicit set.

---

## 3. Feature 2 — Bot persona (gradually-synthesized instruction file)

### 3.1 Concept

```
            (gradual, offline)                         (every message, cheap)
 sources  ──────────────────────▶  compiled persona file  ──────────────▶  LLM prompt
 (notes,        synthesis/                (1 compact doc,                   buildContext()
  uploads)      distillation              token-bounded)
               via AiClient
```

- The client adds information **incrementally** (notes today; uploaded files later).
- A **synthesis job** folds each new/changed source into a single **compiled instruction file**,
  distilled and length-capped. This is the only thing read at chat time.
- **Token efficiency:** runtime injects just the compiled file. The synthesis step itself is also
  incremental — it merges *(current compiled file + the new delta)*, not the entire source
  history — so even compilation stays bounded.

### 3.2 Data model

New collection `tenant_persona` (one doc per tenant):

```kotlin
data class TenantPersona(
    val tenantId: ObjectId,
    val compiledInstructions: String,   // the single file injected at runtime
    val version: Int,
    val tokenEstimate: Int,             // guardrail for runtime cost
    val status: PersonaStatus,          // READY | COMPILING | ERROR
    val updatedAt: Instant,
)
enum class PersonaStatus { EMPTY, COMPILING, READY, ERROR }
```

New collection `persona_sources` (the raw incremental inputs, kept for audit/recompaction):

```kotlin
data class PersonaSource(
    val tenantId: ObjectId,
    val kind: SourceKind,               // TEXT_NOTE | FILE (Phase 3)
    val content: String?,               // text note, or extracted text
    val filePath: String?,              // Phase 3
    val compiledIntoVersion: Int?,      // null until folded in
    val createdAt: Instant,
)
enum class SourceKind { TEXT_NOTE, FILE }
```

> Sources are the gradual "sync" feed; the compiled file is the distilled product. Keeping
> sources lets us re-compact from scratch periodically (Decision D3) without losing inputs.

### 3.3 Runtime injection (`messaging/MessagePipeline.kt`)

Today `buildContext()` (`:269`) starts with the hardcoded `SystemPrompts.CRM_V1`. Change to
**layered** prompting — keep the base, append the tenant persona:

```
system: SystemPrompts.CRM_V1            // tool rules, confirmation/safety logic — unchanged
system: <persona> {compiledInstructions} </persona>   // tenant voice/identity/domain knowledge
... summary, history, user msg ...
```

Layering matters: the persona customizes voice/knowledge but **cannot override** the tool-use and
confirmation safety logic that lives in `CRM_V1` (Decision D2). If a tenant has no persona
(`EMPTY`), nothing is appended.

Delivery into the pipeline:
- `TenantPipelineFactory.build()` already constructs a per-tenant pipeline and passes
  `openrouterModel`. Add the compiled persona string the same way (read once at build).
- **Invalidation:** on persona update, call the existing `pipelineFactory.evict(tenantId)` so the
  next message rebuilds with the fresh file — same pattern already used for tenant edits. No
  per-message DB read, no extra runtime tokens.

### 3.4 Synthesis pipeline (offline)

New `persona/PersonaCompiler.kt`:
- Trigger: when a source is added/edited (or on explicit "rebuild"), enqueue a compile job.
- Job (background coroutine on the existing `pipelineScope`, mirroring the message consumer):
  1. Set persona `status = COMPILING`.
  2. Call `AiClient` with a synthesis prompt: *"Here is the current instruction file + this new
     information. Produce an updated, concise instruction file (≤ N tokens) capturing the bot's
     identity, tone, domain facts, and do/don't rules. Do not include tool mechanics."*
  3. Save new `compiledInstructions`, bump `version`, set `status = READY`, update `tokenEstimate`.
  4. `pipelineFactory.evict(tenantId)`.
- **Debounce** rapid edits (e.g. coalesce within a few seconds) so a burst of additions compiles
  once.
- **Cap** output length (target token budget, e.g. ~800–1200 tokens) to keep runtime cheap.
- On failure: `status = ERROR`, keep the previous compiled file (never serve a broken persona).

### 3.5 Tenant UI (`/app` — `persona` module / inside `settings`)

- **Compiled file panel**: show the current instruction file, its version, `updatedAt`, token
  estimate, and `status` (e.g. "Syncing…" while COMPILING).
- **Add information**: a textarea to paste notes (Phase 1/2); a file upload (Phase 3). Each
  submission creates a `persona_source` and triggers synthesis.
- **Manual edit** (optional, Decision D4): allow editing the compiled file directly; either treat
  the manual edit as authoritative or as another source the next synthesis may revise.
- Tenant API under `/app/api/persona`: `GET` (current + status), `POST /sources` (add),
  `POST /rebuild` (force recompaction). Gated by `requireModule("persona")`.

### 3.6 Phasing

- **Phase 1 — Minimum value. ✅ Done.** Persona model + repo + runtime injection (§3.3) with a
  single client-edited instruction file. The manual textarea is retained (Decision D4).
- **Phase 2 — Gradual sync. ✅ Done.** `persona_sources` collection (`PersonaSource`/`SourceKind`),
  `PersonaCompiler` debounced async synthesis (incremental merge + `rebuild` recompaction), status
  machine (EMPTY/COMPILING/READY/ERROR), `POST /app/api/persona/sources` + `/rebuild`, status UI
  with polling. Compiler evicts the pipeline via `onCompiled` → `pipelineFactory.evict`.
- **Phase 3 — File uploads. ✅ Done.** `POST /app/api/persona/sources/file` (multipart) →
  `PersonaFileExtractor` (PDFBox for PDF, plain for TXT/MD; docx not supported — would need POI) →
  text becomes a `FILE` source fed into synthesis. No vector store; everything distills into the
  one compiled file.

### 3.7 Token budget & cost notes

- Runtime cost is constant: one bounded persona block per message, regardless of how much raw
  material the client uploaded.
- Compile cost is incremental (current file + delta) and infrequent (debounced, on change only).
- Periodic full recompaction from all `persona_sources` (Decision D3) bounds drift, run rarely.

---

## 4. Cross-cutting

- The `persona` editing UI is itself a **module** → governed by Feature 1. Operators enable
  "persona" only for tenants who should manage their own bot voice.
- Both features key off `Tenant` and reuse the existing `tenantRegistry.put` + `pipelineFactory.evict`
  invalidation path — no new cache-coherency machinery.

## 5. Open decisions

- **D1 — Un-disableable modules.** Recommend `settings` always on (so persona/account stay
  reachable). Confirm whether `overview` should also be forced-on.
- **D2 — Persona vs. base prompt.** Recommend layered (persona appended, `CRM_V1` retains tool/
  safety rules). Alternative: full system-prompt replacement (riskier — can break CRM flows).
- **D3 — Recompaction.** Incremental merge by default; periodic full rebuild from `persona_sources`
  to limit drift. Confirm cadence (manual button vs. scheduled).
- **D4 — Manual edit of compiled file.** Allow direct edit, or sources-only? Recommend allow, with
  a "this will be revised on next sync" note.
- **D5 — Who can edit persona.** Tenant only, or operator too (support/impersonation already
  exists via `/admin/api/tenants/{slug}/impersonate`)? Recommend both.

## 6. Test plan

- Feature 1: a tenant with `enabledModules = ["conversations","settings"]` → `/me` returns only
  those; `GET /app/api/crm/quotes` returns 403; nav hides the rest. Legacy tenant
  (`enabledModules = null`) behaves exactly as today.
- Feature 2: adding a source flips status COMPILING→READY and bumps version; pipeline rebuild
  injects the new file; a chat reply reflects the persona; runtime prompt token count stays flat
  as sources grow.
- Safety: a persona that says "ignore confirmation rules" must NOT bypass the CRM confirmation
  guard (layering keeps `CRM_V1` authoritative).

## 7. Effort (rough)

- Feature 1: ~0.5–1 day (model + `modulesFor`/enforcement + operator UI checklist).
- Feature 2 Phase 1: ~1 day (model, repo, runtime injection, basic UI).
- Feature 2 Phase 2: ~1.5–2 days (sources, async compiler, debounce, status UI).
- Feature 2 Phase 3: ~1 day (file extraction).
</content>
