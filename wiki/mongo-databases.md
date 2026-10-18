---
updated: 2026-10-18
---

# Mongo databases

Which of the three Mongo databases a collection belongs in, why the one database was split, how the
split carries data over and survives a rollback, and how backups and restores follow it. Running
backups and restores: [`DEPLOYMENT_RUNBOOK.md`](../DEPLOYMENT_RUNBOOK.md) (Databases, Backups).
Production backup history: [ops-and-deploy.md](ops-and-deploy.md).

## The three databases (PR #85)

One server; the names derive from `app.mongo.database` (`AppConfig.MongoConfig`), so each test
database gets its own pair:

| Database | `MongoModule` | Holds | Backed up |
|---|---|---|---|
| `wabot` | `database` | every company's business data | `data-<stamp>.archive.gz` |
| `wabot_platform` | `platform` | `tenants`, `dashboard_users`, `admin_emails`, `platform_settings`, `tenant_usage`, and `migrations` (the split's marker) | `platform-<stamp>.archive.gz` |
| `wabot_runtime` | `runtime` | `webhook_events`, `scheduler_leases`, `timesheets.challenges` | never |

Rules for new collections:

- **A company's data goes in the data database,** and every document carries the company's
  `tenantId`, or uses it as `_id` for one-per-company settings (`agent_settings`,
  `timesheets.settings`, `dashboard_assistant_settings`). `restore-tenant.sh` finds a company's
  documents by exactly those two fields, so a document tied to its company any other way would not be
  restored.
- **The platform database** holds what belongs to the platform rather than to one company's business:
  the tenant registry and its settings, sign-ins, backoffice operators, platform settings, metering.
- **The runtime database** holds what the app can lose: dedup, leases, short-lived nonces.
- Moving a collection between databases means changing `MongoModule.PLATFORM_COLLECTIONS` /
  `RUNTIME_COLLECTIONS` and `scripts/mongo-databases.sh` together (`MongoModuleLayoutTest` fails
  otherwise), plus a migration like `DatabaseSplit`.
- `$lookup` doesn't cross databases. The only ones (quotes and invoices to their clients) stay inside
  the data database.

## Why split, and not a database per company

Until PR #85 one database held everything. A backup was all of it and a restore rolled back every
company and the platform at once; the archive mixed customers' data with platform settings (secret
overrides included) and disposable state. Rodrigo asked for a restructure so backups stop being messy.

A database per company (or per account) was considered and rejected. Half the repositories resolve
their collection once at construction, and the cross-company queues (agent scheduler and run queue,
domain event dispatcher, Gmail sync, email and location retention) each scan all companies in one
query; all of them would need rewriting to iterate over databases. Every company would add about 45
collections and 110 indexes, each a WiredTiger file, and all data would have to move. Instead the
companies' data stays together, scoped by `tenantId` as it already was; the platform and runtime state
moved out; and `restore-tenant.sh` restores one company from the shared data archive.

## The split migration (`DatabaseSplit`)

- Runs at the start of `MongoModule.initialize()`, before indexes are created.
- **First start** (no `migrations` document `database-split`): copies each moved collection that has
  documents with `$out`, which replaces the target in one step and keeps its indexes, unless the target
  already has documents (the mock seed writes there). It records the `dbHash` of the platform
  originals. The runtime copy keeps the replay of unfinished webhooks working across that deploy.
- **The originals stay in `wabot`,** because the release before the split, which a failed deploy rolls
  back to, only knows them. Every start compares their `dbHash` with the recorded one and copies again
  the platform collections that changed, that is what a rolled-back release wrote; what the split
  release had written to them since is replaced. An emptied or dropped original is never copied, so
  dropping them (runbook, only when Rodrigo asks) is safe. Runtime state is copied once only.
- `dbHash` covers content, not indexes, so the old release creating its indexes at startup changes
  nothing. The backups skip the originals.
- Checked end to end on 2026-10-18 on a production-like Compose stack: the pre-split app made tenants,
  sign-ins, clients and settings; its own backup script took an archive; the split release copied
  everything at boot and served it; then the new backups and all three restore paths; then a rollback
  to the pre-split app, which created a tenant, and the redeployed split release copied it over.

## Backups and restores

- `backup-mongo.sh` writes the platform and data archives and leaves the originals out of the data
  one. It refuses to run when the platform database has no tenants, which means an app from before the
  split is running.
- `restore-tenant.sh` loads a data archive (or a `mongo-` one from before the split) into a scratch
  database with `mongorestore --nsFrom/--nsTo --noIndexRestore`, then per collection deletes the
  company's documents and `$merge`s its archived ones back. Standalone Mongo has no transactions, so
  it isn't atomic: suspend the company first, and pause its agents (queued runs come back queued).
  The slug reaches mongosh as an environment variable.
- `restore-mongo.sh` restores whole databases. A `mongo-` archive goes into the new layout through
  `nsFrom/nsTo` and skips the runtime collections, so the originals' hashes don't change and nothing
  is copied again at the next start.
- The deploy installs the host scripts only after the health checks pass (`deploy.yml`), so after a
  rollback the host keeps the scripts that match the release running. A nightly backup that runs in
  the minute between the new app starting and the scripts arriving makes one more `mongo-` archive.

Open: the originals in `wabot` go only by hand. There is no per-company export to hand a customer its
data.
