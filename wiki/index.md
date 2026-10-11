# Index

One line per page. Read this first, and open a page only when its line matches the task. How the
wiki works, and what must never go in it: [README.md](README.md).

## Product and architecture

- [overview.md](overview.md) — what the product is, the message pipeline, modules and channels, rules that apply everywhere
- [architecture-review.md](architecture-review.md) — the 2026-09-28 review: access policy, webhook replay, conversation lanes, tool-to-module tables, hot spots
- [tenants-and-companies.md](tenants-and-companies.md) — a tenant holding several companies: data scope, users, token switching, soft delete and restore

## Features

- [crm-directories.md](crm-directories.md) — clients, suppliers and employees: record drawer, fields, delete or archive, client finances, per-company client fields, importing into prod
- [catalog-services-bookings.md](catalog-services-bookings.md) — catalog titles and codes, Serviços rows, the line items editor, bookings as catalog services
- [billing-and-pdfs.md](billing-and-pdfs.md) — quotes, invoices (ATCUD, installments, cancel and delete), payments, euro rounding, PDF generation
- [employee-portal-and-time-clock.md](employee-portal-and-time-clock.md) — employee sign-ins, service submissions, the opt-in time clock
- [inbox-and-channels.md](inbox-and-channels.md) — Conversations inbox (AI pause, 24-hour window, ticks, templates, media), the Email page (Gmail threads, suggested dashboard actions), website widget, Instagram
- [whatsapp-and-meta.md](whatsapp-and-meta.md) — Embedded Signup, webhook fields, display names, payment methods, App Review, template permissions
- [ai-persona-and-bot.md](ai-persona-and-bot.md) — the customer-facing bot: persona studio, prompts, tools, human handoff
- [ai-assistant.md](ai-assistant.md) — the dashboard AI Assistant: its prompt, checked cards, settings, history
- [agents-automations.md](agents-automations.md) — the opt-in Agents module, domain events, background jobs, Gmail and Google verification
- [auth-and-access.md](auth-and-access.md) — Google sign-in for the backoffice and `/app`, admin emails, where the access rules live
- [dashboard-ui.md](dashboard-ui.md) — why the UI looks as it does: the Clean Ops skin, backoffice records, phones, the column picker

## Mobile

- [mobile.md](mobile.md) — the KMP companion app: its state, gaps against the backend, recent changes
- [kmp-engineering-guide.md](kmp-engineering-guide.md) — **binding** for all work in `mobile/`

## Running it

- [ops-and-deploy.md](ops-and-deploy.md) — production hosting, deploy by SHA, backups, platform settings over `.env`, VPS script traps
- [gotchas.md](gotchas.md) — cross-cutting traps: kotlinx defaults, JUnit, Testcontainers, i18n proxies, `hidden`, local UI checks, GitNexus
- [cloud-agents.md](cloud-agents.md) — Cursor cloud agents: environment, PR history, how their PRs get merged
