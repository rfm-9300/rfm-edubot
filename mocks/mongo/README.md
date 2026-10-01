# Mongo Mock Data

This folder contains deterministic local seed data for the WhatsApp bot MongoDB.

Run it with a local Mongo container:

```bash
docker exec -i whatsapp-bot-mongo-1 mongosh --quiet --file /dev/stdin < mocks/mongo/create-mocks.js
```

The script targets database `wabot` by default. Override with `MONGO_DATABASE` if needed:

```bash
MONGO_DATABASE=wabot_dev docker exec -i whatsapp-bot-mongo-1 mongosh --quiet --file /dev/stdin < mocks/mongo/create-mocks.js
```

The script deletes and recreates only its known seed records. It does not drop the database, delete Mongo volumes, or remove unrelated local data.

Seeded collections:

- `users`
- `conversations`
- `messages`
- `webhook_events`
- `crm.clients`
- `crm.quotes`
- `crm.invoices`
- `crm.suppliers`
- `crm.employees`
- `crm.payments`
- `crm.sequences`
- `crm.standard_items`
- `bookings.services`
- `bookings.availability`
- `bookings.appointments`
- `agents`
- `agent_runs`
- `agent_approvals`
- `agent_tasks`
- `notifications`
- `integration_connections`
- `email_messages`

Bookings, agents and email are seeded only when the database already has a tenant; they go to the first one. Start the app once before seeding a fresh database.

Agents and email:

- The Agents module is opt-in. A company only sees it once the backoffice turns it on in the company's modules.
- The agents' runs, approvals, tasks, notifications and emails are dated from when the script runs, so the pending approval doesn't expire and the 90-day email clean-up doesn't remove them. Run the script again to refresh them.
- The active agents don't start on the seeded invoices and quotes by themselves. Nothing is sent until someone approves the pending WhatsApp reminder.
- The Gmail account `orcamentos@example.com` has no tokens and shows "needs reconnect". Nothing calls Google. Connecting a real account needs the Google OAuth settings in `.env.example`.
