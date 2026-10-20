---
updated: 2026-10-19
---

# Running as one instance

The server is built to run as one process. This page covers what that process keeps only in its
memory, what already works across processes, and what would have to move to shared state before a
second instance could run. The table by component is in
[`docs/architecture.md` → Single-instance state](../docs/architecture.md#single-instance-state);
hosting is in [ops-and-deploy.md](ops-and-deploy.md).

## The decision

Production is one app container (plus Mongo) on one VPS, and a release replaces it rather than
running two side by side ([ops-and-deploy.md](ops-and-deploy.md)). Several guarantees are kept in
memory: one conversation's messages answered in order, customers' rate limits, booking overlaps,
the company limit. With one instance they hold; with two they would quietly weaken rather than fail.
So no shared infrastructure is built until a second instance is needed (decided in PR #87, which only
documents it). Anything that would run two at once, a deploy that overlaps the old and new containers
included, needs the work below first.

## Already safe across instances

- Background jobs run on a Mongo lease (`SchedulerLease`, `wabot_runtime`): the agents' scheduler,
  Gmail sync and the retention jobs run on one instance at a time.
- Webhook dedup is a unique `eventId` in `webhook_events`, and storing the customer's message is
  idempotent on `(tenantId, waMessageId)`.
- Domain events and agent runs are claimed atomically in Mongo (`DomainEventLog.claimNext`,
  `AgentRunRepository.claim`), so two dispatchers never handle the same event or run.
- A phone is unique per company for clients, suppliers and employees by unique indexes, not by a
  check in memory.

## What would have to move

**Correctness.** These give wrong results with two instances.

- The inbound queue and the conversation lanes (`MessageQueue`, `ConversationLanes`) are in memory.
  One conversation's messages stay in order only inside one process, and at startup each instance
  re-queues every event still `received` from the last 30 minutes, including ones another instance is
  answering. They would become a Mongo work queue claimed per conversation (as domain events are), or
  each conversation would be routed to one instance.
- The rate limiter is one `RateLimiter` per cached tenant pipeline, per process, reset whenever the
  pipeline is evicted (a persona or settings change). Two instances would double every customer's
  quota. The counters would move to `wabot_runtime` with a TTL.
- `BookingScheduler` checks for an overlap and inserts under a per-company `Mutex`, and
  `CompanyService` counts companies and creates one under a per-tenant `Mutex`. Each would need a lock
  document or a conditional write in Mongo.
- `OAuthState` remembers the OAuth states it has consumed, per process. One-time use across instances
  needs a collection with a unique state and a TTL.

**Freshness.** A change made through one instance reaches the other only at its restart. Today the
instance that makes the change reloads its own copy.

- `TenantRegistry` (which company a channel's webhooks belong to), the `TenantPipelineFactory` cache
  (persona, modules, limits), `RuntimeConfig`'s platform settings and the admin list (`AdminAccess`),
  and the agents' active list and `DomainEventInterest` (which events the pipeline records for
  agents). Each would need to reload on a change signal: a Mongo change stream, or a version document
  polled every few seconds.

**Delivery.**

- A website-widget visitor's WebSocket lives on the instance that accepted it (`WebChannelRegistry`),
  so only that instance can send the reply. It needs sticky sessions plus a way to hand a reply to the
  instance holding the socket.
- `DomainEventSignal` wakes only the local dispatcher; another instance picks the event up on its next
  poll, which costs latency, not correctness.

**Fine to leave per instance.** Duplicated work or a per-instance cap, never a wrong answer: the
WhatsApp template caches (5 minutes) in `InboxService` and `WhatsAppTemplateSending`,
`EmailInsightsService` sharing one reading between simultaneous requests, `GoogleTokenProvider`'s
refresh lock, `PersonaCompiler`'s debounce and lock (two instances could synthesize the same persona
twice, and both resume pending work at boot), `AgentRunExecutor`'s per-company limit (it becomes
per instance) and `DeduplicationService`'s mutex (the unique index decides).
